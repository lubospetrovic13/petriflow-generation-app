package com.petriflow.backend;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import okhttp3.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.TimeUnit;

@Service
public class GeminiService {

    private static final Logger log = LoggerFactory.getLogger(GeminiService.class);
    private static final String STREAM_URL =
        "https://generativelanguage.googleapis.com/v1beta/models/%s:streamGenerateContent?alt=sse&key=%s";
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    @Autowired private AppConfig      config;
    @Autowired private ContextService ctx;
    @Autowired private RunLogger      runLogger;

    private final ObjectMapper mapper = new ObjectMapper();
    private final OkHttpClient http   = new OkHttpClient.Builder()
            .connectTimeout(60, TimeUnit.SECONDS)
            .readTimeout(600, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build();

    public void chatStream(List<Map<String, String>> messages, SseEmitter emitter) {
        new Thread(() -> {
            long startMs = System.currentTimeMillis();
            RunLogger.RunEntry entry = new RunLogger.RunEntry();
            entry.userPrompt = firstUserPrompt(messages);
            StringBuilder fullText = new StringBuilder();

            try {
                List<Map<String, String>> finalMessages;

                if (config.isRagMode()) {
                    String context = ctx.retrieveContext(messages);
                    finalMessages = ctx.injectContextIntoMessages(messages, context);
                } else {
                    finalMessages = messages;
                }

                String requestBody = buildRequest(finalMessages);
                log.info("Sending Gemini request, body length: {} chars", requestBody.length());
                String url = String.format(STREAM_URL, config.geminiModel, config.geminiApiKey);

                Request req = new Request.Builder()
                        .url(url)
                        .header("Content-Type", "application/json")
                        .post(RequestBody.create(requestBody, JSON))
                        .build();

                log.info("Calling Gemini API...");
                Response resp = http.newCall(req).execute();
                log.info("Gemini response code: {}", resp.code());

                if (resp.code() == 429) {
                    log.warn("Gemini rate limited - sending error to frontend");
                    try {
                        emitter.send(SseEmitter.event().name("error").data("⚠ Rate limited by Gemini — please wait 1 minute and try again"));
                        emitter.complete();
                    } catch (Exception e) {
                        log.error("Failed to send rate limit error to frontend: {}", e.getMessage());
                    }
                    resp.close();
                    return;
                }
                if (resp.code() != 200) {
                    String err = resp.body() != null ? resp.body().string() : "Unknown error";
                    log.error("Gemini API error {}: {}", resp.code(), err);
                    try {
                        emitter.send(SseEmitter.event().name("error").data("Gemini error " + resp.code() + ": " + err));
                        emitter.complete();
                    } catch (Exception e) {
                        log.error("Failed to send error to frontend: {}", e.getMessage());
                    }
                    resp.close();
                    return;
                }

                BufferedReader reader = new BufferedReader(
                        new InputStreamReader(resp.body().byteStream(), StandardCharsets.UTF_8));

                String line;
                while ((line = reader.readLine()) != null) {
                    if (!line.startsWith("data: ")) continue;
                    String jsonStr = line.substring(6).trim();
                    if ("[DONE]".equals(jsonStr)) break;

                    try {
                        JsonNode chunk = mapper.readTree(jsonStr);
                        JsonNode parts = chunk.path("candidates").path(0).path("content").path("parts");
                        String finishReason = chunk.path("candidates").path(0)
                                .path("finishReason").asText("");

                        JsonNode usage = chunk.path("usageMetadata");
                        if (!usage.isMissingNode()) {
                            entry.promptTokens = usage.path("promptTokenCount").asLong();
                            entry.outputTokens = usage.path("candidatesTokenCount").asLong();
                        }

                        if (!parts.isMissingNode() && parts.isArray() && parts.size() > 0) {
                            String text = parts.get(0).path("text").asText("");
                            if (!text.isEmpty()) {
                                fullText.append(text);
                                emitter.send(SseEmitter.event().name("chunk")
                                        .data(mapper.writeValueAsString(Map.of("text", text))));
                            }
                        }

                        if ("MAX_TOKENS".equals(finishReason)) {
                            entry.truncated = true;
                            emitter.send(SseEmitter.event().name("chunk")
                                    .data(mapper.writeValueAsString(Map.of("text",
                                            "\n\n<!-- ⚠️ RESPONSE TRUNCATED — increase gemini.max-output-tokens -->"))));
                        }
                        if (!finishReason.isEmpty()) entry.stopReason = finishReason;

                    } catch (Exception ignored) {}
                }

                entry.durationMs = System.currentTimeMillis() - startMs;

                // Validate XML and retry if errors found
                String finalXml = fullText.toString();
                XmlValidator.ValidationResult validation = XmlValidator.validate(finalXml);

                if (!validation.isValid) {
                    log.warn("XML validation failed with {} errors, attempting retry", validation.errors.size());
                    entry.retry = true;

                    // Send notification to user
                    String notification = XmlRetryHelper.buildUserNotification(validation.errors.size());
                    emitter.send(SseEmitter.event().name("chunk")
                            .data(mapper.writeValueAsString(Map.of("text", notification))));

                    // Build retry request
                    String retryPrompt = XmlRetryHelper.buildRetryPrompt(entry.userPrompt, finalXml, validation.errors);
                    List<Map<String, String>> retryMessages = new ArrayList<>();
                    Map<String, String> retryMsg = new HashMap<>();
                    retryMsg.put("role", "user");
                    retryMsg.put("content", retryPrompt);
                    retryMessages.add(retryMsg);

                    // Second API call
                    String retryBody = buildRequest(retryMessages);
                    String retryUrl = String.format(STREAM_URL, config.geminiModel, config.geminiApiKey);
                    Request retryReq = new Request.Builder()
                            .url(retryUrl)
                            .header("Content-Type", "application/json")
                            .post(RequestBody.create(retryBody, JSON))
                            .build();

                    log.info("Calling Gemini API for retry...");
                    Response retryResp = http.newCall(retryReq).execute();

                    if (retryResp.code() == 200) {
                        fullText = new StringBuilder();
                        BufferedReader retryReader = new BufferedReader(
                                new InputStreamReader(retryResp.body().byteStream(), StandardCharsets.UTF_8));

                        String retryLine;
                        while ((retryLine = retryReader.readLine()) != null) {
                            if (!retryLine.startsWith("data: ")) continue;
                            String retryJsonStr = retryLine.substring(6).trim();
                            if ("[DONE]".equals(retryJsonStr)) break;

                            try {
                                JsonNode chunk = mapper.readTree(retryJsonStr);
                                JsonNode parts = chunk.path("candidates").path(0).path("content").path("parts");

                                if (!parts.isMissingNode() && parts.isArray() && parts.size() > 0) {
                                    String text = parts.get(0).path("text").asText("");
                                    if (!text.isEmpty()) {
                                        fullText.append(text);
                                        emitter.send(SseEmitter.event().name("chunk")
                                                .data(mapper.writeValueAsString(Map.of("text", text))));
                                    }
                                }

                                JsonNode usage = chunk.path("usageMetadata");
                                if (!usage.isMissingNode()) {
                                    entry.outputTokens += usage.path("candidatesTokenCount").asLong();
                                }
                            } catch (Exception ignored) {}
                        }
                        retryResp.close();
                        finalXml = fullText.toString();
                        log.info("Retry completed successfully");
                    } else {
                        log.warn("Retry request failed with code {}", retryResp.code());
                        retryResp.close();
                    }
                }

                runLogger.log(entry);

                emitter.send(SseEmitter.event().name("done")
                        .data(mapper.writeValueAsString(Map.of("full", finalXml))));
                emitter.complete();

            } catch (Exception e) {
                log.error("Gemini stream error: {}", e.getMessage(), e);
                try {
                    entry.durationMs = System.currentTimeMillis() - startMs;
                    runLogger.log(entry);
                    emitter.send(SseEmitter.event().name("error").data(e.getMessage()));
                    emitter.complete();
                } catch (Exception ignored) {}
            }
        }).start();
    }

    private String buildRequest(List<Map<String, String>> messages) throws Exception {
        ObjectNode body = mapper.createObjectNode();

        // System instruction (works for all context modes)
        String systemText;
        if (config.isRagMode()) {
            systemText = ContextService.SYSTEM_PROMPT;
        } else {
            // Use rules-only for debug, rules+patterns for generation
            String guide = ctx.isDebugRequest(messages) ? ctx.getRulesOnly() : ctx.getCompressedGuide();
            systemText = ContextService.SYSTEM_PROMPT + "\n\n" + guide;
        }

        ObjectNode sysInstr = mapper.createObjectNode();
        ArrayNode sysParts = mapper.createArrayNode();
        ObjectNode sysPart = mapper.createObjectNode();
        sysPart.put("text", systemText);
        sysParts.add(sysPart);
        sysInstr.set("parts", sysParts);
        body.set("system_instruction", sysInstr);

        // Messages
        ArrayNode contents = mapper.createArrayNode();
        for (Map<String, String> msg : messages) {
            ObjectNode content = mapper.createObjectNode();
            // Gemini uses "model" instead of "assistant"
            content.put("role", "assistant".equals(msg.get("role")) ? "model" : "user");
            ArrayNode parts = mapper.createArrayNode();
            ObjectNode part = mapper.createObjectNode();
            part.put("text", msg.get("content"));
            parts.add(part);
            content.set("parts", parts);
            contents.add(content);
        }
        body.set("contents", contents);

        // Generation config
        ObjectNode genConfig = mapper.createObjectNode();
        genConfig.put("maxOutputTokens", config.geminiMaxTokens);
        genConfig.put("temperature", 0.2);

        // Thinking config (only relevant for gemini-2.5-flash)
        ObjectNode thinkingConfig = mapper.createObjectNode();
        thinkingConfig.put("thinkingBudget", config.geminiThinkingBudget);
        genConfig.set("thinkingConfig", thinkingConfig);

        body.set("generationConfig", genConfig);

        return mapper.writeValueAsString(body);
    }

    private String firstUserPrompt(List<Map<String, String>> messages) {
        return messages.stream()
                .filter(m -> "user".equals(m.get("role")))
                .map(m -> m.get("content"))
                .findFirst().orElse("").substring(0, Math.min(200,
                        messages.stream().filter(m -> "user".equals(m.get("role")))
                                .map(m -> m.get("content")).findFirst().orElse("").length()));
    }
}
