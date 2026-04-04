package com.petriflow.backend;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.nio.file.*;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Persists user-supplied settings (API keys, GitHub config, model choices, etc.)
 * to a JSON file on the mounted volume, then applies them to AppConfig at runtime.
 *
 * File location: same directory as chats.json — controlled by SETTINGS_STORAGE_PATH
 * env var (default: settings.json).
 *
 * On startup: load settings.json → override AppConfig fields.
 * On save:    update AppConfig fields → write settings.json.
 */
@Service
public class SettingsService {

    private static final Logger log = LoggerFactory.getLogger(SettingsService.class);

    @Autowired
    private AppConfig config;

    @Value("${settings.storage.path:settings.json}")
    private String settingsPath;

    private final ObjectMapper mapper = new ObjectMapper();

    @PostConstruct
    public void loadSettings() {
        Path path = Paths.get(settingsPath);
        if (!Files.exists(path)) {
            log.info("No settings.json found at {} — using application.properties defaults", path.toAbsolutePath());
            return;
        }
        try {
            String json = Files.readString(path);
            Map<?, ?> s = mapper.readValue(json, Map.class);
            applyToConfig(s);
            log.info("Settings loaded from {}", path.toAbsolutePath());
        } catch (Exception e) {
            log.warn("Failed to load settings.json: {} — continuing with defaults", e.getMessage());
        }
    }

    /**
     * Returns the current settings as a map (API keys masked for frontend display).
     */
    public Map<String, Object> getSettings() {
        Map<String, Object> s = new LinkedHashMap<>();

        // API keys — send masked values so frontend knows they are set
        s.put("anthropicApiKey",  maskKey(config.anthropicApiKey));
        s.put("openaiApiKey",     maskKey(config.openaiApiKey));
        s.put("geminiApiKey",     maskKey(config.geminiApiKey));

        // Models
        s.put("claudeModel",  config.claudeModel);
        s.put("openaiModel",  config.openaiModel);
        s.put("geminiModel",  config.geminiModel);
        s.put("ollamaModel",  config.ollamaModel);
        s.put("ollamaBaseUrl", config.ollamaBaseUrl);

        // Token limits
        s.put("claudeMaxTokens",  config.claudeMaxTokens);
        s.put("openaiMaxTokens",  config.openaiMaxTokens);
        s.put("geminiMaxTokens",  config.geminiMaxTokens);
        s.put("ollamaMaxTokens",  config.ollamaMaxTokens);

        // Thinking
        s.put("claudeThinkingEnabled", config.claudeThinkingEnabled);
        s.put("claudeThinkingBudget",  config.claudeThinkingBudget);
        s.put("geminiThinkingBudget",  config.geminiThinkingBudget);

        // GitHub
        s.put("githubToken",    maskKey(config.githubToken));
        s.put("githubUsername", config.githubUsername);
        s.put("githubRepo",     config.githubRepo);

        // eTask
        s.put("eTaskEmail",    config.eTaskEmail);
        s.put("eTaskPassword", maskKey(config.eTaskPassword));

        // RAG
        s.put("ragTopK",          config.ragTopK);
        s.put("ragAlwaysInclude", config.ragAlwaysInclude);

        // Embed provider
        s.put("embedProvider", config.embedProvider);

        // Provider + mode (moved from chat UI to settings)
        s.put("llmProvider",  config.llmProvider);
        s.put("contextMode",  config.contextMode);

        return s;
    }

    /**
     * Saves the incoming settings map: applies to AppConfig, then persists to disk.
     * API key fields that arrive as masked ("sk-...***") are left unchanged.
     */
    public void saveSettings(Map<String, Object> incoming) throws Exception {
        // Load existing raw settings so we can merge (keep stored keys if masked arrives)
        Map<String, Object> stored = loadRaw();

        // Merge: overwrite stored with incoming, but skip masked key values
        for (Map.Entry<String, Object> e : incoming.entrySet()) {
            String k = e.getKey();
            Object v = e.getValue();
            if (v instanceof String && ((String) v).contains("***")) {
                // masked — keep stored value
                continue;
            }
            stored.put(k, v);
        }

        applyToConfig(stored);

        Path path = Paths.get(settingsPath);
        Files.writeString(path, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(stored));
        log.info("Settings saved to {}", path.toAbsolutePath());
    }

    /**
     * Returns true if at least one API key is configured for the requested provider.
     */
    public boolean hasKeyForProvider(String provider) {
        switch (provider.toLowerCase()) {
            case "claude": return isSet(config.anthropicApiKey);
            case "openai": return isSet(config.openaiApiKey);
            case "gemini": return isSet(config.geminiApiKey);
            default:       return false;
        }
    }

    /**
     * Returns a summary of which keys are configured (for health/status endpoint).
     */
    public Map<String, Boolean> keysConfigured() {
        Map<String, Boolean> m = new LinkedHashMap<>();
        m.put("claude",  isSet(config.anthropicApiKey));
        m.put("openai",  isSet(config.openaiApiKey));
        m.put("gemini",  isSet(config.geminiApiKey));
        m.put("github",  isSet(config.githubToken) && isSet(config.githubUsername) && isSet(config.githubRepo));
        m.put("etask",  isSet(config.eTaskEmail) && isSet(config.eTaskPassword));
        return m;
    }

    // ── Internals ─────────────────────────────────────────────────────────────

    private void applyToConfig(Map<?, ?> s) {
        str(s, "anthropicApiKey",  v -> config.anthropicApiKey  = v);
        str(s, "openaiApiKey",     v -> config.openaiApiKey     = v);
        str(s, "geminiApiKey",     v -> config.geminiApiKey     = v);

        str(s, "claudeModel",      v -> config.claudeModel      = v);
        str(s, "openaiModel",      v -> config.openaiModel      = v);
        str(s, "geminiModel",      v -> config.geminiModel      = v);
        str(s, "ollamaModel",      v -> config.ollamaModel      = v);
        str(s, "ollamaBaseUrl",    v -> config.ollamaBaseUrl    = v);
        str(s, "llmProvider",      v -> config.llmProvider      = v);
        str(s, "contextMode",      v -> config.contextMode      = v);

        str(s, "embedProvider",    v -> config.embedProvider     = v);
        str(s, "ragAlwaysInclude", v -> config.ragAlwaysInclude  = v);

        str(s, "githubToken",    v -> config.githubToken    = v);
        str(s, "githubUsername", v -> config.githubUsername = v);
        str(s, "githubRepo",     v -> config.githubRepo     = v);

        str(s, "eTaskEmail",    v -> config.eTaskEmail    = v);
        str(s, "eTaskPassword", v -> config.eTaskPassword = v);

        intVal(s, "claudeMaxTokens",  v -> config.claudeMaxTokens  = v);
        intVal(s, "openaiMaxTokens",  v -> config.openaiMaxTokens  = v);
        intVal(s, "geminiMaxTokens",  v -> config.geminiMaxTokens  = v);
        intVal(s, "ollamaMaxTokens",  v -> config.ollamaMaxTokens  = v);
        intVal(s, "claudeThinkingBudget",  v -> config.claudeThinkingBudget  = v);
        intVal(s, "geminiThinkingBudget",  v -> config.geminiThinkingBudget  = v);
        intVal(s, "ragTopK",           v -> config.ragTopK           = v);

        boolVal(s, "claudeThinkingEnabled", v -> config.claudeThinkingEnabled = v);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> loadRaw() {
        try {
            Path path = Paths.get(settingsPath);
            if (Files.exists(path)) {
                return mapper.readValue(Files.readString(path), LinkedHashMap.class);
            }
        } catch (Exception ignored) {}
        return new LinkedHashMap<>();
    }

    private void str(Map<?, ?> s, String key, java.util.function.Consumer<String> setter) {
        Object v = s.get(key);
        if (v instanceof String && !((String) v).isBlank()) setter.accept((String) v);
    }

    private void intVal(Map<?, ?> s, String key, java.util.function.Consumer<Integer> setter) {
        Object v = s.get(key);
        if (v instanceof Number) setter.accept(((Number) v).intValue());
        else if (v instanceof String) { try { setter.accept(Integer.parseInt((String) v)); } catch (Exception ignored) {} }
    }

    private void boolVal(Map<?, ?> s, String key, java.util.function.Consumer<Boolean> setter) {
        Object v = s.get(key);
        if (v instanceof Boolean) setter.accept((Boolean) v);
        else if (v instanceof String) setter.accept(Boolean.parseBoolean((String) v));
    }

    private boolean isSet(String v) { return v != null && !v.isBlank(); }

    private String maskKey(String key) {
        if (!isSet(key)) return "";
        if (key.length() <= 8) return "***";
        return key.substring(0, 6) + "***" + key.substring(key.length() - 4);
    }
}