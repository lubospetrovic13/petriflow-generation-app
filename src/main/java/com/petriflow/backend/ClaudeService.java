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
public class ClaudeService {

    private static final Logger log = LoggerFactory.getLogger(ClaudeService.class);
    private static final String API_URL = "https://api.anthropic.com/v1/messages";
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

                String requestBody = buildStreamRequest(finalMessages);
                log.info("Sending Claude request, body length: {} chars", requestBody.length());

                Request req = new Request.Builder()
                        .url(API_URL)
                        .header("x-api-key", config.anthropicApiKey)
                        .header("anthropic-version", "2023-06-01")
                        .header("anthropic-beta", buildBetaHeader())
                        .header("Content-Type", "application/json")
                        .post(RequestBody.create(requestBody, JSON))
                        .build();

                log.info("Calling Claude API...");
                Response resp = http.newCall(req).execute();
                log.info("Claude response code: {}", resp.code());

                if (resp.code() == 429) {
                    log.warn("Claude rate limited - sending error to frontend");
                    try {
                        emitter.send(SseEmitter.event().name("error").data("⚠ Rate limited by Claude — please wait and retry"));
                        emitter.complete();
                    } catch (Exception e) {
                        log.error("Failed to send rate limit error to frontend: {}", e.getMessage());
                    }
                    resp.close();
                    return;
                }
                if (resp.code() != 200) {
                    String err = resp.body() != null ? resp.body().string() : "Unknown error";
                    log.error("Claude API error {}: {}", resp.code(), err);
                    try {
                        emitter.send(SseEmitter.event().name("error").data("Claude error " + resp.code() + ": " + err));
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
                        String type = chunk.path("type").asText("");

                        // Usage info (comes in message_start)
                        if ("message_start".equals(type)) {
                            JsonNode usage = chunk.path("message").path("usage");
                            entry.promptTokens     = usage.path("input_tokens").asLong();
                            entry.cacheReadTokens  = usage.path("cache_read_input_tokens").asLong();
                            entry.cacheWriteTokens = usage.path("cache_creation_input_tokens").asLong();
                            entry.thinkingTokens   = usage.path("thinking_tokens").asLong(0);
                        }

                        // Content block start (check if it's thinking)
                        if ("content_block_start".equals(type)) {
                            String blockType = chunk.path("content_block").path("type").asText("");
                            if ("thinking".equals(blockType)) {
                                log.debug("Thinking block started");
                                // Optionally send notification to user
                                emitter.send(SseEmitter.event().name("chunk")
                                        .data(mapper.writeValueAsString(Map.of("text", "\n💭 _Thinking..._\n\n"))));
                            }
                        }

                        // Text delta
                        if ("content_block_delta".equals(type)) {
                            String deltaType = chunk.path("delta").path("type").asText("");
                            String text = chunk.path("delta").path("text").asText("");

                            // Skip thinking content from final output
                            if ("thinking_delta".equals(deltaType)) {
                                log.debug("Thinking delta: {}", text.length() > 50 ? text.substring(0, 50) + "..." : text);
                                // Don't add to fullText, don't send to user
                            } else if ("text_delta".equals(deltaType) && !text.isEmpty()) {
                                fullText.append(text);
                                emitter.send(SseEmitter.event().name("chunk")
                                        .data(mapper.writeValueAsString(Map.of("text", text))));
                            }
                        }

                        // Final usage (message_delta has output tokens)
                        if ("message_delta".equals(type)) {
                            JsonNode usage = chunk.path("usage");
                            entry.outputTokens = usage.path("output_tokens").asLong();
                            // Thinking tokens may also be updated here
                            if (usage.has("thinking_tokens")) {
                                entry.thinkingTokens = usage.path("thinking_tokens").asLong(0);
                            }
                            entry.stopReason   = chunk.path("delta").path("stop_reason").asText("stop");
                            if ("max_tokens".equals(entry.stopReason)) {
                                entry.truncated = true;
                                emitter.send(SseEmitter.event().name("chunk")
                                        .data(mapper.writeValueAsString(Map.of("text",
                                                "\n\n<!-- ⚠️ RESPONSE TRUNCATED — increase claude.max-output-tokens -->"))));
                            }
                        }
                    } catch (Exception ignored) {}
                }

                entry.durationMs = System.currentTimeMillis() - startMs;

                // Validate XML and retry if errors found
                String finalXml = fullText.toString();
                XmlValidator.ValidationResult validation = XmlValidator.validate(finalXml, config);

                // Show eTask-only errors as info (do NOT retry — LLM cannot fix eTask server errors)
                if (!validation.eTaskErrors.isEmpty()) {
                    String eTaskNote = "⚠️ eTask validation: " + String.join("; ", validation.eTaskErrors);
                    emitter.send(SseEmitter.event().name("chunk")
                            .data(mapper.writeValueAsString(Map.of("text", eTaskNote))));
                }

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
                    String retryBody = buildStreamRequest(retryMessages);
                    Request retryReq = new Request.Builder()
                            .url(API_URL)
                            .header("x-api-key", config.anthropicApiKey)
                            .header("anthropic-version", "2023-06-01")
                            .header("anthropic-beta", buildBetaHeader())
                            .header("Content-Type", "application/json")
                            .post(RequestBody.create(retryBody, JSON))
                            .build();

                    log.info("Calling Claude API for retry...");
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
                                String type = chunk.path("type").asText("");

                                if ("content_block_delta".equals(type)) {
                                    String text = chunk.path("delta").path("text").asText("");
                                    if (!text.isEmpty()) {
                                        fullText.append(text);
                                        emitter.send(SseEmitter.event().name("chunk")
                                                .data(mapper.writeValueAsString(Map.of("text", text))));
                                    }
                                }

                                if ("message_delta".equals(type)) {
                                    JsonNode usage = chunk.path("usage");
                                    entry.outputTokens += usage.path("output_tokens").asLong();
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
                log.error("Claude stream error: {}", e.getMessage(), e);
                try {
                    entry.durationMs = System.currentTimeMillis() - startMs;
                    runLogger.log(entry);
                    emitter.send(SseEmitter.event().name("error").data(e.getMessage()));
                    emitter.complete();
                } catch (Exception ignored) {}
            }
        }).start();
    }

    // ── Request builders ──────────────────────────────────────────────────────

    private String buildStreamRequest(List<Map<String, String>> messages) throws Exception {
        ObjectNode body = mapper.createObjectNode();
        body.put("model", config.claudeModel);
        body.put("max_tokens", config.claudeMaxTokens);
        body.put("stream", true);

        // Extended Thinking support (requires claude-3-7-sonnet or newer)
        if (config.claudeThinkingEnabled && config.claudeThinkingBudget > 0) {
            ObjectNode thinkingConfig = mapper.createObjectNode();
            thinkingConfig.put("type", "enabled");
            thinkingConfig.put("budget_tokens", config.claudeThinkingBudget);
            body.set("thinking", thinkingConfig);
            log.info("Extended thinking enabled with budget: {} tokens", config.claudeThinkingBudget);
        }

        if (config.isRagMode()) {
            // RAG: system prompt only, guide injected into user message
            body.put("system", ContextService.SYSTEM_PROMPT);
        } else {
            // Full mode: compressed guide in system prompt with prompt-caching
            ArrayNode sysArray = mapper.createArrayNode();
            ObjectNode sysBlock = mapper.createObjectNode();
            sysBlock.put("type", "text");
            // Use rules-only for debug, rules+patterns for generation
            String guide = ctx.isDebugRequest(messages) ? ctx.getRulesOnly() : ctx.getCompressedGuide();
            sysBlock.put("text", guide);
            ObjectNode cacheCtrl = mapper.createObjectNode();
            cacheCtrl.put("type", "ephemeral");
            sysBlock.set("cache_control", cacheCtrl);
            sysArray.add(sysBlock);
            body.set("system", sysArray);
        }

        ArrayNode msgsArray = mapper.createArrayNode();
        for (Map<String, String> msg : messages) {
            ObjectNode n = mapper.createObjectNode();
            n.put("role", msg.get("role"));
            n.put("content", msg.get("content"));
            msgsArray.add(n);
        }
        body.set("messages", msgsArray);

        return mapper.writeValueAsString(body);
    }

    private String buildBetaHeader() {
        List<String> features = new ArrayList<>();

        // Extended thinking (if enabled) - GA feature, no beta header needed
        // If you need interleaved thinking specifically, use: interleaved-thinking-2025-05-14

        // Prompt caching (only in full mode, not RAG)
        if (config.isFullMode()) {
            features.add("prompt-caching-2024-07-31");
        }

        // Extended output
        features.add("output-128k-2025-02-19");

        return String.join(",", features);
    }

    private String firstUserPrompt(List<Map<String, String>> messages) {
        return messages.stream()
                .filter(m -> "user".equals(m.get("role")))
                .map(m -> m.get("content"))
                .findFirst().orElse("")
                .substring(0, Math.min(200, messages.isEmpty() ? 0 :
                        messages.get(0).getOrDefault("content", "").length()));
    }
}