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
import java.util.ArrayList;
import java.util.stream.*;

@RestController
@RequestMapping("/api")
public class ChatController {

    private static final Logger log = LoggerFactory.getLogger(ChatController.class);

    @Autowired private AppConfig      config;
    @Autowired private ClaudeService  claudeService;
    @Autowired private OpenAIService  openAIService;
    @Autowired private GeminiService  geminiService;
    @Autowired private OllamaService  ollamaService;
    @Autowired private RagService     ragService;
    @Autowired private SettingsService settingsService;

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
        } else if (config.isOllama()) {
            ollamaService.chatStream(request.getMessages(), emitter);
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

        if (provider != null && (provider.equals("claude") || provider.equals("openai") || provider.equals("gemini") || provider.equals("ollama"))) {
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
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("status",      "ok");
        info.put("llmProvider", config.llmProvider);
        info.put("model",       config.activeLlmModel());
        info.put("contextMode", config.contextMode);
        info.put("keysConfigured", settingsService.keysConfigured());
        return ResponseEntity.ok(info);
    }

    /**
     * GET /api/settings
     * Returns current settings (API keys masked).
     */
    @GetMapping("/settings")
    public ResponseEntity<?> getSettings() {
        return ResponseEntity.ok(settingsService.getSettings());
    }

    /**
     * POST /api/settings
     * Saves and applies new settings at runtime.
     */
    @PostMapping("/settings")
    public ResponseEntity<?> saveSettings(@RequestBody Map<String, Object> body) {
        try {
            settingsService.saveSettings(body);
            return ResponseEntity.ok(Map.of(
                    "status", "ok",
                    "keysConfigured", settingsService.keysConfigured()
            ));
        } catch (Exception e) {
            log.error("Failed to save settings: {}", e.getMessage());
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
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
     * POST /api/upload-etask
     * Authenticates with eTask (Basic Auth), uploads the XML process via
     * POST /api/petrinet/import (multipart), and returns success.
     * Credentials are read from AppConfig (set via Settings panel).
     * The frontend then redirects the user to https://etask.netgrif.cloud/portal/cases
     */
    /**
     * POST /api/upload-etask
     * Login → import XML → return redirectUrl. No role assignment, no cleanup.
     * (Validation + cleanup is handled by XmlValidator step 16 during generation.)
     */
    @PostMapping("/upload-etask")
    public ResponseEntity<?> uploadEtask(@RequestBody String xmlContent) {
        final String ETASK_BASE = "https://etask.netgrif.cloud";

        if (!isSet(config.eTaskEmail) || !isSet(config.eTaskPassword)) {
            return ResponseEntity.status(400).body(Map.of(
                    "error", "eTask credentials not configured. Add your email and password in Settings."));
        }

        try {
            String credentials = java.util.Base64.getEncoder().encodeToString(
                    (config.eTaskEmail + ":" + config.eTaskPassword)
                            .getBytes(java.nio.charset.StandardCharsets.UTF_8));

            OkHttpClient client = new OkHttpClient.Builder()
                    .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                    .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
                    .build();

            // Step 1: Authenticate — capture session cookies from response
            okhttp3.Request loginReq = new okhttp3.Request.Builder()
                    .url(ETASK_BASE + "/api/auth/login")
                    .header("Authorization", "Basic " + credentials)
                    .header("Accept", "application/hal+json")
                    .get().build();

            List<String> sessionCookies = new ArrayList<>();
            try (okhttp3.Response loginResp = client.newCall(loginReq).execute()) {
                if (!loginResp.isSuccessful()) {
                    return ResponseEntity.status(401).body(Map.of(
                            "error", "eTask authentication failed (HTTP " + loginResp.code()
                                    + "). Check your email and password in Settings."));
                }
                // Capture all Set-Cookie headers so we can forward them to the browser
                sessionCookies = loginResp.headers("Set-Cookie");
                log.info("eTask login OK for {} — got {} session cookie(s)", config.eTaskEmail, sessionCookies.size());
            }

            // Step 2: Prepare XML
            String xmlToUpload = xmlContent.trim().startsWith("<?xml")
                    ? xmlContent
                    : "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" + xmlContent;

            java.util.regex.Matcher idMatcher = java.util.regex.Pattern
                    .compile("<id>([^<]+)</id>").matcher(xmlToUpload);
            String processIdentifier = idMatcher.find() ? idMatcher.group(1).trim() : "process";

            java.util.regex.Matcher verMatcher = java.util.regex.Pattern
                    .compile("<version>([^<]+)</version>").matcher(xmlToUpload);
            String processVersion = verMatcher.find() ? verMatcher.group(1).trim() : "1.0.0";

            byte[] xmlBytes = xmlToUpload.getBytes(java.nio.charset.StandardCharsets.UTF_8);

            // Step 3: Import
            okhttp3.RequestBody filePart = okhttp3.RequestBody.create(
                    xmlBytes, okhttp3.MediaType.get("application/octet-stream"));
            okhttp3.MultipartBody multipart = new okhttp3.MultipartBody.Builder()
                    .setType(okhttp3.MultipartBody.FORM)
                    .addFormDataPart("file", processIdentifier + ".xml", filePart)
                    .build();
            okhttp3.Request importReq = new okhttp3.Request.Builder()
                    .url(ETASK_BASE + "/api/petrinet/import")
                    .header("Authorization", "Basic " + credentials)
                    .header("Accept", "application/hal+json")
                    .post(multipart).build();

            try (okhttp3.Response importResp = client.newCall(importReq).execute()) {
                String respBody = importResp.body() != null ? importResp.body().string() : "";
                if (!importResp.isSuccessful()) {
                    String reason = diagnoseImportFailure(client, credentials, ETASK_BASE,
                            processIdentifier, processVersion, importResp.code());
                    log.warn("eTask upload failed: HTTP {} — {}", importResp.code(), respBody);
                    return ResponseEntity.status(importResp.code()).body(Map.of(
                            "error", "eTask import failed: " + reason));
                }
                log.info("eTask upload OK for identifier={} version={}", processIdentifier, processVersion);
            }

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", "ok");
            result.put("redirectUrl", "https://etask.netgrif.cloud/portal/cases");
            result.put("email", config.eTaskEmail);
            return ResponseEntity.ok(result);

        } catch (Exception e) {
            log.error("eTask upload error: {}", e.getMessage(), e);
            return ResponseEntity.status(500).body(Map.of("error", e.getMessage()));
        }
    }


    /**
     * Diagnoses why a petrinet import failed.
     * Checks if a net with same identifier+version already exists.
     * Returns a human-readable reason string.
     */
    private String diagnoseImportFailure(OkHttpClient client, String credentials,
                                         String base, String identifier, String version, int httpCode) {
        try {
            // Search for existing net with same identifier
            String searchBody = mapper.writeValueAsString(Map.of("identifier", identifier));
            okhttp3.Request req = new okhttp3.Request.Builder()
                    .url(base + "/api/petrinet/search?page=0&size=5&sort=createdDate,desc")
                    .header("Authorization", "Basic " + credentials)
                    .header("Accept", "application/hal+json")
                    .header("Content-Type", "application/json")
                    .post(okhttp3.RequestBody.create(
                            searchBody.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                            okhttp3.MediaType.get("application/json")))
                    .build();

            try (okhttp3.Response resp = client.newCall(req).execute()) {
                if (!resp.isSuccessful()) {
                    return "HTTP " + httpCode + " from eTask (could not diagnose further).";
                }
                String body = resp.body() != null ? resp.body().string() : "{}";
                com.fasterxml.jackson.databind.JsonNode json = mapper.readTree(body);
                com.fasterxml.jackson.databind.JsonNode nets = json
                        .path("_embedded").path("petriNetReferenceResources");

                if (nets.isArray() && nets.size() > 0) {
                    // Check if exact version already exists
                    for (com.fasterxml.jackson.databind.JsonNode net : nets) {
                        if (version.equals(net.path("version").asText(""))) {
                            return "A process with identifier '" + identifier + "' version '" + version +
                                    "' already exists in eTask. Bump the <version> in your XML and try again.";
                        }
                    }
                    return "A process with identifier '" + identifier + "' exists but with a different version. " +
                            "If eTask rejected this version, try bumping <version> in your XML.";
                }
            }
        } catch (Exception e) {
            log.debug("diagnoseImportFailure error: {}", e.getMessage());
        }
        return "HTTP " + httpCode + " from eTask. Check that your account has the ADMIN role.";
    }

    /** Recursively searches a JSON tree for the first non-empty "stringId" value. */
    private String findStringId(com.fasterxml.jackson.databind.JsonNode node) {
        if (node == null) return null;
        if (node.isObject()) {
            com.fasterxml.jackson.databind.JsonNode v = node.get("stringId");
            if (v != null && v.isTextual() && !v.asText().isEmpty()) return v.asText();
            java.util.Iterator<com.fasterxml.jackson.databind.JsonNode> fields = node.elements();
            while (fields.hasNext()) {
                String found = findStringId(fields.next());
                if (found != null) return found;
            }
        } else if (node.isArray()) {
            for (com.fasterxml.jackson.databind.JsonNode child : node) {
                String found = findStringId(child);
                if (found != null) return found;
            }
        }
        return null;
    }

    /** Searches for a net by identifier and returns its stringId. */
    private String findNetIdByIdentifier(OkHttpClient client, String credentials,
                                         String base, String identifier) {
        try {
            String searchBody = mapper.writeValueAsString(Map.of("identifier", identifier));
            okhttp3.Request req = new okhttp3.Request.Builder()
                    .url(base + "/api/petrinet/search?page=0&size=1&sort=createdDate,desc")
                    .header("Authorization", "Basic " + credentials)
                    .header("Accept", "application/hal+json")
                    .header("Content-Type", "application/json")
                    .post(okhttp3.RequestBody.create(
                            searchBody.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                            okhttp3.MediaType.get("application/json")))
                    .build();
            try (okhttp3.Response resp = client.newCall(req).execute()) {
                if (!resp.isSuccessful()) return null;
                String body = resp.body() != null ? resp.body().string() : "";
                com.fasterxml.jackson.databind.JsonNode nets = mapper.readTree(body)
                        .path("_embedded").path("petriNetReferenceResources");
                if (nets.isArray() && nets.size() > 0) {
                    return nets.get(0).path("stringId").asText(null);
                }
            }
        } catch (Exception e) {
            log.warn("findNetIdByIdentifier failed: {}", e.getMessage());
        }
        return null;
    }

    private boolean isSet(String v) { return v != null && !v.isBlank(); }
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