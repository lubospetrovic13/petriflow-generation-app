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
import javax.net.ssl.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.*;
import java.util.concurrent.TimeUnit;

@Service
public class OpenAIService {

    private static final Logger log = LoggerFactory.getLogger(OpenAIService.class);
    private static final String API_URL = "https://api.openai.com/v1/chat/completions";
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    @Autowired private AppConfig      config;
    @Autowired private ContextService ctx;
    @Autowired private RunLogger      runLogger;

    private final ObjectMapper mapper = new ObjectMapper();
    private final OkHttpClient http   = createSecureOkHttpClient();

    private OkHttpClient createSecureOkHttpClient() {
        try {
            // Initialize trust manager with default certificates
            TrustManagerFactory trustManagerFactory = TrustManagerFactory.getInstance(
                    TrustManagerFactory.getDefaultAlgorithm());
            trustManagerFactory.init((KeyStore) null);
            TrustManager[] trustManagers = trustManagerFactory.getTrustManagers();

            // Initialize SSL context with TLS
            SSLContext sslContext = SSLContext.getInstance("TLS");
            sslContext.init(null, trustManagers, new java.security.SecureRandom());

            // Configure modern TLS connection spec
            ConnectionSpec spec = new ConnectionSpec.Builder(ConnectionSpec.MODERN_TLS)
                    .tlsVersions(TlsVersion.TLS_1_2, TlsVersion.TLS_1_3)
                    .build();

            return new OkHttpClient.Builder()
                    .connectTimeout(30, TimeUnit.SECONDS)
                    .readTimeout(600, TimeUnit.SECONDS)
                    .writeTimeout(30, TimeUnit.SECONDS)
                    .sslSocketFactory(sslContext.getSocketFactory(),
                            (X509TrustManager) trustManagers[0])
                    .connectionSpecs(Arrays.asList(spec, ConnectionSpec.COMPATIBLE_TLS))
                    .build();
        } catch (Exception e) {
            log.error("Failed to create secure OkHttpClient, falling back to default", e);
            // Fallback to basic client if SSL setup fails
            return new OkHttpClient.Builder()
                    .connectTimeout(30, TimeUnit.SECONDS)
                    .readTimeout(600, TimeUnit.SECONDS)
                    .writeTimeout(30, TimeUnit.SECONDS)
                    .build();
        }
    }

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
                log.info("Sending OpenAI request, body length: {} chars", requestBody.length());

                Request req = new Request.Builder()
                        .url(API_URL)
                        .header("Authorization", "Bearer " + config.openaiApiKey)
                        .header("Content-Type", "application/json")
                        .post(RequestBody.create(requestBody, JSON))
                        .build();

                log.info("Calling OpenAI API...");
                Response resp = http.newCall(req).execute();
                log.info("OpenAI response code: {}", resp.code());

                if (resp.code() == 429) {
                    log.warn("OpenAI rate limited - sending error to frontend");
                    try {
                        emitter.send(SseEmitter.event().name("error").data("⚠ Rate limited by OpenAI — please wait 1 minute and try again"));
                        emitter.complete();
                    } catch (Exception e) {
                        log.error("Failed to send rate limit error to frontend: {}", e.getMessage());
                    }
                    resp.close();
                    return;
                }
                if (resp.code() != 200) {
                    String err = resp.body() != null ? resp.body().string() : "Unknown error";
                    log.error("OpenAI API error {}: {}", resp.code(), err);
                    try {
                        emitter.send(SseEmitter.event().name("error").data("OpenAI error " + resp.code() + ": " + err));
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
                        String finishReason = chunk.path("choices").path(0)
                                .path("finish_reason").asText("");

                        String text = chunk.path("choices").path(0)
                                .path("delta").path("content").asText("");

                        if (!text.isEmpty()) {
                            fullText.append(text);
                            emitter.send(SseEmitter.event().name("chunk")
                                    .data(mapper.writeValueAsString(Map.of("text", text))));
                        }

                        // Usage is in the last chunk when stream_options.include_usage=true
                        JsonNode usage = chunk.path("usage");
                        if (!usage.isMissingNode()) {
                            entry.promptTokens = usage.path("prompt_tokens").asLong();
                            entry.outputTokens = usage.path("completion_tokens").asLong();
                        }

                        if ("length".equals(finishReason)) {
                            entry.truncated = true;
                            emitter.send(SseEmitter.event().name("chunk")
                                    .data(mapper.writeValueAsString(Map.of("text",
                                            "\n\n<!-- ⚠️ RESPONSE TRUNCATED — increase openai.max-output-tokens -->"))));
                        }
                        if (!finishReason.isEmpty()) entry.stopReason = finishReason;

                    } catch (Exception ignored) {}
                }

                entry.durationMs = System.currentTimeMillis() - startMs;

                // Validate XML and retry if errors found
                String finalXml = fullText.toString();
                XmlValidator.ValidationResult validation = XmlValidator.validate(finalXml, config);

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
                    Request retryReq = new Request.Builder()
                            .url(API_URL)
                            .header("Authorization", "Bearer " + config.openaiApiKey)
                            .header("Content-Type", "application/json")
                            .post(RequestBody.create(retryBody, JSON))
                            .build();

                    log.info("Calling OpenAI API for retry...");
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

                                JsonNode usage = chunk.path("usage");
                                if (!usage.isMissingNode()) {
                                    entry.outputTokens += usage.path("completion_tokens").asLong();
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
                log.error("OpenAI stream error: {}", e.getMessage(), e);
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
        body.put("model", config.openaiModel);
        body.put("max_tokens", config.openaiMaxTokens);
        body.put("stream", true);
        body.put("temperature", 0.2);

        // Include token usage in the final stream chunk
        ObjectNode streamOpts = mapper.createObjectNode();
        streamOpts.put("include_usage", true);
        body.set("stream_options", streamOpts);

        ArrayNode msgsArray = mapper.createArrayNode();

        // System message
        ObjectNode sys = mapper.createObjectNode();
        sys.put("role", "system");
        if (config.isRagMode()) {
            sys.put("content", ContextService.SYSTEM_PROMPT);
        } else {
            // Use rules-only for debug, rules+patterns for generation
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
        return messages.stream()
                .filter(m -> "user".equals(m.get("role")))
                .map(m -> m.get("content"))
                .findFirst().orElse("").substring(0, Math.min(200,
                        messages.stream().filter(m -> "user".equals(m.get("role")))
                                .map(m -> m.get("content")).findFirst().orElse("").length()));
    }
}