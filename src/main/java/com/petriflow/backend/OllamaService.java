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

/**
 * Ollama local LLM service.
 * Uses the OpenAI-compatible /v1/chat/completions endpoint that Ollama exposes.
 * Base URL configurable via settings (default: http://localhost:11434).
 */
@Service
public class OllamaService {

    private static final Logger log = LoggerFactory.getLogger(OllamaService.class);
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    @Autowired private AppConfig      config;
    @Autowired private ContextService ctx;
    @Autowired private RunLogger      runLogger;

    private final ObjectMapper mapper = new ObjectMapper();
    private final OkHttpClient http   = new OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
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

                String apiUrl = config.ollamaBaseUrl.replaceAll("/+$", "") + "/v1/chat/completions";
                String requestBody = buildRequest(finalMessages);
                log.info("Sending Ollama request to {} body length: {} chars", apiUrl, requestBody.length());

                Request req = new Request.Builder()
                        .url(apiUrl)
                        .header("Content-Type", "application/json")
                        .post(RequestBody.create(requestBody, JSON))
                        .build();

                log.info("Calling Ollama API...");
                Response resp = http.newCall(req).execute();
                log.info("Ollama response code: {}", resp.code());

                if (resp.code() != 200) {
                    String err = resp.body() != null ? resp.body().string() : "Unknown error";
                    log.error("Ollama API error {}: {}", resp.code(), err);
                    emitter.send(SseEmitter.event().name("error")
                            .data("Ollama error " + resp.code() + ": " + err));
                    emitter.complete();
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
                        String text = chunk.path("choices").path(0)
                                .path("delta").path("content").asText("");
                        String finishReason = chunk.path("choices").path(0)
                                .path("finish_reason").asText("");

                        if (!text.isEmpty()) {
                            fullText.append(text);
                            emitter.send(SseEmitter.event().name("chunk")
                                    .data(mapper.writeValueAsString(Map.of("text", text))));
                        }
                        if ("length".equals(finishReason)) {
                            entry.truncated = true;
                            emitter.send(SseEmitter.event().name("chunk")
                                    .data(mapper.writeValueAsString(Map.of("text",
                                            "\n\n<!-- ⚠️ RESPONSE TRUNCATED — increase ollama.max-output-tokens -->"))));
                        }
                        if (!finishReason.isEmpty()) entry.stopReason = finishReason;
                    } catch (Exception ignored) {}
                }

                entry.durationMs = System.currentTimeMillis() - startMs;

                // Validate XML and retry if errors found
                String finalXml = fullText.toString();
                XmlValidator.ValidationResult validation = XmlValidator.validate(finalXml, config);

                // Show eTask-only errors as info (do NOT retry — LLM cannot fix eTask server errors)
                if (!validation.eTaskErrors.isEmpty()) {
                    String eTaskNote = "\n\n\u26a0\ufe0f eTask validation: " + String.join("; ", validation.eTaskErrors) + "\n";
                    emitter.send(SseEmitter.event().name("chunk")
                            .data(mapper.writeValueAsString(Map.of("text", eTaskNote))));
                }

                if (!validation.isValid) {
                    log.warn("XML validation failed with {} errors, attempting retry", validation.errors.size());
                    entry.retry = true;

                    String notification = XmlRetryHelper.buildUserNotification(validation.errors.size());
                    emitter.send(SseEmitter.event().name("chunk")
                            .data(mapper.writeValueAsString(Map.of("text", notification))));

                    String retryPrompt = XmlRetryHelper.buildRetryPrompt(entry.userPrompt, finalXml, validation.errors);
                    List<Map<String, String>> retryMessages = new ArrayList<>();
                    Map<String, String> retryMsg = new HashMap<>();
                    retryMsg.put("role", "user");
                    retryMsg.put("content", retryPrompt);
                    retryMessages.add(retryMsg);

                    String retryBody = buildRequest(retryMessages);
                    Request retryReq = new Request.Builder()
                            .url(apiUrl)
                            .header("Content-Type", "application/json")
                            .post(RequestBody.create(retryBody, JSON))
                            .build();

                    log.info("Calling Ollama API for retry...");
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
                                String text = chunk.path("choices").path(0)
                                        .path("delta").path("content").asText("");
                                if (!text.isEmpty()) {
                                    fullText.append(text);
                                    emitter.send(SseEmitter.event().name("chunk")
                                            .data(mapper.writeValueAsString(Map.of("text", text))));
                                }
                            } catch (Exception ignored) {}
                        }
                        retryResp.close();
                        finalXml = fullText.toString();
                        log.info("Ollama retry completed successfully");
                    } else {
                        log.warn("Ollama retry failed with code {}", retryResp.code());
                        retryResp.close();
                    }
                }

                runLogger.log(entry);
                emitter.send(SseEmitter.event().name("done")
                        .data(mapper.writeValueAsString(Map.of("full", finalXml))));
                emitter.complete();

            } catch (Exception e) {
                log.error("Ollama stream error: {}", e.getMessage(), e);
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
        body.put("model", config.ollamaModel);
        body.put("max_tokens", config.ollamaMaxTokens);
        body.put("stream", true);
        body.put("temperature", 0.2);

        ArrayNode msgsArray = mapper.createArrayNode();

        // System message with guide
        ObjectNode sys = mapper.createObjectNode();
        sys.put("role", "system");
        if (config.isRagMode()) {
            sys.put("content", ContextService.SYSTEM_PROMPT);
        } else {
            String guide = ctx.isDebugRequest(messages) ? ctx.getRulesOnly() : ctx.getCompressedGuide();
            sys.put("content", ContextService.SYSTEM_PROMPT + "\n\n# Petriflow Guide\n\n" + guide);
        }
        msgsArray.add(sys);

        for (Map<String, String> msg : messages) {
            ObjectNode n = mapper.createObjectNode();
            n.put("role", msg.get("role"));
            n.put("content", msg.get("content"));
            msgsArray.add(n);
        }
        body.set("messages", msgsArray);
        return mapper.writeValueAsString(body);
    }

    private String firstUserPrompt(List<Map<String, String>> messages) {
        String content = messages.stream()
                .filter(m -> "user".equals(m.get("role")))
                .map(m -> m.get("content"))
                .findFirst().orElse("");
        return content.substring(0, Math.min(200, content.length()));
    }
}