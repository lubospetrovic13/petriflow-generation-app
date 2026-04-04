package com.petriflow.backend;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Central configuration — read once at startup, used by all services.
 * Change application.properties and restart to switch providers/modes.
 */
@Component
public class AppConfig {

    // ── Provider choices ──────────────────────────────────────────────────────

    /** LLM provider: "claude" | "openai" | "gemini" */
    @Value("${llm.provider:gemini}")
    public String llmProvider;

    /** Context mode: "full" (whole guide) | "rag" (top-K chunks) */
    @Value("${context.mode:rag}")
    public String contextMode;

    /** Embedding provider (only used when context.mode=rag): "openai" | "gemini" */
    @Value("${embed.provider:gemini}")
    public String embedProvider;

    // ── API keys ──────────────────────────────────────────────────────────────

    @Value("${anthropic.api-key:}")
    public String anthropicApiKey;

    @Value("${openai.api-key:}")
    public String openaiApiKey;

    @Value("${gemini.api-key:}")
    public String geminiApiKey;

    // ── Models ────────────────────────────────────────────────────────────────

    @Value("${claude.model:claude-sonnet-4-5}")
    public String claudeModel;

    @Value("${openai.model:gpt-4o}")
    public String openaiModel;

    @Value("${gemini.model:gemini-2.5-flash}")
    public String geminiModel;

    @Value("${ollama.base-url:http://localhost:11434}")
    public String ollamaBaseUrl;

    @Value("${ollama.model:llama3}")
    public String ollamaModel;

    // ── Token limits ──────────────────────────────────────────────────────────

    @Value("${claude.max-output-tokens:32000}")
    public int claudeMaxTokens;

    @Value("${openai.max-output-tokens:16000}")
    public int openaiMaxTokens;

    @Value("${gemini.max-output-tokens:65536}")
    public int geminiMaxTokens;

    @Value("${ollama.max-output-tokens:8192}")
    public int ollamaMaxTokens;

    // ── Thinking ──────────────────────────────────────────────────────────────

    @Value("${claude.thinking.enabled:false}")
    public boolean claudeThinkingEnabled;

    @Value("${claude.thinking.budget:4000}")
    public int claudeThinkingBudget;

    @Value("${gemini.thinking-budget:0}")
    public int geminiThinkingBudget;

    // ── RAG ───────────────────────────────────────────────────────────────────

    @Value("${rag.top-k:14}")
    public int ragTopK;

    @Value("${rag.always-include:7.,8.,9.}")
    public String ragAlwaysInclude;

    // ── Logging ───────────────────────────────────────────────────────────────

    @Value("${run.log.path:run-log.jsonl}")
    public String runLogPath;

    @Value("${chat.storage.path:chats.json}")
    public String chatStoragePath;

    @Value("${settings.storage.path:settings.json}")
    public String settingsStoragePath;

    // ── GitHub ────────────────────────────────────────────────────────────────

    @Value("${github.token:}")
    public String githubToken;

    @Value("${github.username:}")
    public String githubUsername;

    @Value("${github.repo:}")
    public String githubRepo;

    // ── eTask ─────────────────────────────────────────────────────────────────

    @Value("${etask.email:}")
    public String eTaskEmail;

    @Value("${etask.password:}")
    public String eTaskPassword;

    // ── Helpers ───────────────────────────────────────────────────────────────

    public boolean isClaude()  { return "claude".equalsIgnoreCase(llmProvider); }
    public boolean isOpenAI()  { return "openai".equalsIgnoreCase(llmProvider); }
    public boolean isGemini()  { return "gemini".equalsIgnoreCase(llmProvider); }
    public boolean isOllama()  { return "ollama".equalsIgnoreCase(llmProvider); }
    public boolean isRagMode()     { return "rag".equalsIgnoreCase(contextMode); }
    public boolean isFullMode()    { return "full".equalsIgnoreCase(contextMode); }
    public boolean isOpenAIEmbed() { return "openai".equalsIgnoreCase(embedProvider); }

    /** Returns the active model name for the configured LLM provider. */
    public String activeLlmModel() {
        if (isClaude())  return claudeModel;
        if (isOpenAI())  return openaiModel;
        if (isOllama())  return ollamaModel;
        return geminiModel;
    }

    /** Returns the max output token limit for the configured LLM provider. */
    public int activeMaxTokens() {
        if (isClaude())  return claudeMaxTokens;
        if (isOpenAI())  return openaiMaxTokens;
        if (isOllama())  return ollamaMaxTokens;
        return geminiMaxTokens;
    }

    @Override
    public String toString() {
        return String.format("llm=%s/%s context=%s embed=%s topK=%d",
                llmProvider, activeLlmModel(), contextMode, embedProvider, ragTopK);
    }
}