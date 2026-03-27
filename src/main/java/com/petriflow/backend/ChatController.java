package com.petriflow.backend;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.nio.file.*;
import java.util.*;
import java.util.stream.*;

@RestController
@RequestMapping("/api")
public class ChatController {

    private static final Logger log = LoggerFactory.getLogger(ChatController.class);

    @Autowired private AppConfig     config;
    @Autowired private ClaudeService claudeService;
    @Autowired private OpenAIService openAIService;
    @Autowired private GeminiService geminiService;
    @Autowired private RagService    ragService;

    private final ObjectMapper mapper = new ObjectMapper();

    /**
     * POST /api/chat/stream
     * Body: { "messages": [ { "role": "user", "content": "..." } ] }
     * Returns an SSE stream with events: "chunk" {text}, "done" {full}, "error" "message"
     */
    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chatStream(@RequestBody ChatRequest request) {
        log.info("Stream request: {} | {} messages", config, request.getMessages().size());
        SseEmitter emitter = new SseEmitter(600_000L);

        if (config.isClaude()) {
            claudeService.chatStream(request.getMessages(), emitter);
        } else if (config.isOpenAI()) {
            openAIService.chatStream(request.getMessages(), emitter);
        } else {
            geminiService.chatStream(request.getMessages(), emitter);
        }
        return emitter;
    }

    /**
     * GET /api/config
     * Returns current active configuration.
     */
    @GetMapping("/config")
    public ResponseEntity<?> getConfig() {
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("llmProvider",   config.llmProvider);
        info.put("model",         config.activeLlmModel());
        info.put("contextMode",   config.contextMode);
        info.put("embedProvider", config.isRagMode() ? config.embedProvider : "n/a");
        info.put("ragTopK",       config.isRagMode() ? config.ragTopK : 0);
        info.put("maxTokens",     config.activeMaxTokens());
        if (config.isClaude()) {
            info.put("thinkingEnabled", config.claudeThinkingEnabled);
            if (config.claudeThinkingEnabled) {
                info.put("thinkingBudget", config.claudeThinkingBudget);
            }
        }
        if (config.isGemini()) {
            info.put("thinkingBudget", config.geminiThinkingBudget);
        }
        return ResponseEntity.ok(info);
    }

    /**
     * POST /api/config/update
     * Updates LLM provider and context mode dynamically
     */
    @PostMapping("/config/update")
    public ResponseEntity<?> updateConfig(@RequestBody Map<String, String> updates) {
        String provider = updates.get("provider");
        String mode = updates.get("mode");

        if (provider != null && (provider.equals("claude") || provider.equals("openai") || provider.equals("gemini"))) {
            config.llmProvider = provider;
            log.info("LLM provider updated to: {}", provider);
        }
        if (mode != null && (mode.equals("full") || mode.equals("rag"))) {
            config.contextMode = mode;
            log.info("Context mode updated to: {}", mode);
        }

        return ResponseEntity.ok(Map.of(
            "status", "ok",
            "provider", config.llmProvider,
            "mode", config.contextMode
        ));
    }

    /**
     * GET /api/health
     */
    @GetMapping("/health")
    public ResponseEntity<?> health() {
        return ResponseEntity.ok(Map.of(
                "status",      "ok",
                "llmProvider", config.llmProvider,
                "model",       config.activeLlmModel(),
                "contextMode", config.contextMode
        ));
    }

    /**
     * POST /api/reload
     * Recomputes RAG embeddings from the current petriflow_reference.md.
     */
    @PostMapping("/reload")
    public ResponseEntity<?> reload() {
        try {
            ragService.reload();
            return ResponseEntity.ok(Map.of("status", "ok", "message", "RAG embeddings recomputed"));
        } catch (Exception e) {
            log.error("Reload error: {}", e.getMessage());
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * GET /api/guide
     * Returns the raw petriflow_guide.md as plain text.
     * The frontend renders it via marked.js in the slide-in docs panel.
     */
    @GetMapping(value = "/guide", produces = "text/plain;charset=UTF-8")
    public ResponseEntity<String> getGuide() {
        try {
            org.springframework.core.io.ClassPathResource res =
                new org.springframework.core.io.ClassPathResource("guides/petriflow_guide.md");
            String content = new String(res.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            return ResponseEntity.ok()
                .header("Access-Control-Allow-Origin", "*")
                .body(content);
        } catch (Exception e) {
            log.error("Guide load error: {}", e.getMessage());
            return ResponseEntity.internalServerError().body("Error loading guide: " + e.getMessage());
        }
    }

    /**
     * POST /api/upload-xml
     * Uploads XML to GitHub and returns raw.githubusercontent.com URL
     */
    @PostMapping("/upload-xml")
    public ResponseEntity<?> uploadXml(@RequestBody String xmlContent) {
        try {
            // Generate unique filename with timestamp + random UUID
            String timestamp = String.valueOf(System.currentTimeMillis());
            String randomId = UUID.randomUUID().toString().substring(0, 8);
            String filename = "process_" + timestamp + "_" + randomId + ".xml";
            String path = filename; // Upload to repo root

            // Base64 encode the content (GitHub API requires base64)
            String base64Content = java.util.Base64.getEncoder()
                    .encodeToString(xmlContent.getBytes(java.nio.charset.StandardCharsets.UTF_8));

            // Build GitHub API request
            ObjectMapper om = new ObjectMapper();
            Map<String, Object> requestBody = new LinkedHashMap<>();
            requestBody.put("message", "Add generated Petriflow process");
            requestBody.put("content", base64Content);

            String jsonBody = om.writeValueAsString(requestBody);

            OkHttpClient client = new OkHttpClient.Builder()
                    .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                    .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                    .build();

            String apiUrl = String.format("https://api.github.com/repos/%s/%s/contents/%s",
                    config.githubUsername, config.githubRepo, path);

            okhttp3.Request request = new okhttp3.Request.Builder()
                    .url(apiUrl)
                    .header("Authorization", "Bearer " + config.githubToken)
                    .header("Accept", "application/vnd.github+json")
                    .header("Content-Type", "application/json")
                    .put(okhttp3.RequestBody.create(jsonBody.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                            okhttp3.MediaType.get("application/json")))
                    .build();

            try (okhttp3.Response response = client.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    String err = response.body() != null ? response.body().string() : "Unknown error";
                    log.error("GitHub upload failed: HTTP {} - {}", response.code(), err);
                    return ResponseEntity.status(500).body(Map.of("error", "GitHub upload failed: " + err));
                }

                // Return raw GitHub URL (no CORS issues)
                String rawUrl = String.format("https://raw.githubusercontent.com/%s/%s/main/%s",
                        config.githubUsername, config.githubRepo, path);

                log.info("XML uploaded to GitHub: {}", rawUrl);
                return ResponseEntity.ok(Map.of("url", rawUrl));
            }
        } catch (Exception e) {
            log.error("XML upload error: {}", e.getMessage(), e);
            return ResponseEntity.status(500).body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * GET /api/runs?limit=20
     * Returns the last N run log entries as a JSON array (newest first).
     */
    @GetMapping("/runs")
    public ResponseEntity<?> getRuns(@RequestParam(defaultValue = "20") int limit) {
        try {
            Path logPath = Paths.get(config.runLogPath);
            if (!Files.exists(logPath)) {
                return ResponseEntity.ok(Collections.emptyList());
            }
            List<String> lines = Files.readAllLines(logPath);
            List<Object> entries = lines.stream()
                    .skip(Math.max(0, lines.size() - limit))
                    .map(l -> {
                        try { return (Object) mapper.readTree(l); }
                        catch (Exception e) { return (Object) l; }
                    })
                    .collect(Collectors.toList());
            Collections.reverse(entries); // newest first
            return ResponseEntity.ok(entries);
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * GET /api/chats
     * Returns all saved chats from backend storage
     */
    @GetMapping("/chats")
    public ResponseEntity<?> getChats() {
        try {
            Path chatPath = Paths.get(config.chatStoragePath);
            if (!Files.exists(chatPath)) {
                return ResponseEntity.ok(Collections.emptyList());
            }
            String content = Files.readString(chatPath);
            return ResponseEntity.ok(mapper.readTree(content));
        } catch (Exception e) {
            log.error("Failed to load chats: {}", e.getMessage());
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * POST /api/chats
     * Saves all chats to backend storage
     */
    @PostMapping("/chats")
    public ResponseEntity<?> saveChats(@RequestBody List<Map<String, Object>> chats) {
        try {
            Path chatPath = Paths.get(config.chatStoragePath);
            String json = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(chats);
            Files.writeString(chatPath, json);
            log.info("Saved {} chats to {}", chats.size(), config.chatStoragePath);
            return ResponseEntity.ok(Map.of("status", "ok", "count", chats.size()));
        } catch (Exception e) {
            log.error("Failed to save chats: {}", e.getMessage());
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    // ── DTOs ──────────────────────────────────────────────────────────────────

    public static class ChatRequest {
        @JsonProperty("messages")
        private List<Map<String, String>> messages;
        public List<Map<String, String>> getMessages() { return messages; }
        public void setMessages(List<Map<String, String>> m) { this.messages = m; }
    }
}
