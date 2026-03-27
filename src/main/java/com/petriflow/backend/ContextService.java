package com.petriflow.backend;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Prepares the Petriflow guide context to inject into each LLM call.
 *
 * context.mode=full  → returns the entire guide (used with Claude prompt-cache)
 * context.mode=rag   → retrieves the top-K relevant chunks via RagService
 */
@Service
public class ContextService {

    private static final Logger log = LoggerFactory.getLogger(ContextService.class);

    @Autowired private AppConfig   config;
    @Autowired private RagService  ragService;

    private String fullGuide;       // petriflow_guide.md - for docs panel only
    private String referenceGuide;  // petriflow_reference.md - source of truth for generation

    @PostConstruct
    public void loadGuide() throws IOException {
        // Load original guide (kept for docs panel in UI)
        ClassPathResource res = new ClassPathResource("guides/petriflow_guide.md");
        fullGuide = new String(res.getInputStream().readAllBytes(), StandardCharsets.UTF_8);

        // Load reference guide - THE source of truth for LLM generation
        ClassPathResource refRes = new ClassPathResource("guides/petriflow_reference.md");
        referenceGuide = new String(refRes.getInputStream().readAllBytes(), StandardCharsets.UTF_8);

        log.info("Original guide (docs only): {} chars (~{} tokens)", fullGuide.length(), fullGuide.length() / 4);
        log.info("Reference guide (LLM source): {} chars (~{} tokens)", referenceGuide.length(), referenceGuide.length() / 4);
    }

    /**
     * Returns the full guide text (for docs panel in UI only).
     */
    public String getFullGuide() {
        return fullGuide;
    }

    /**
     * Returns the reference guide - THE single source of truth for LLM generation.
     * Used in both full mode (Claude prompt-cache) and RAG mode.
     */
    public String getCompressedGuide() {
        return referenceGuide;
    }

    /**
     * Returns the reference guide (same as getCompressedGuide).
     * No distinction between debug/generation anymore - single source of truth.
     */
    public String getRulesOnly() {
        return referenceGuide;
    }

    /**
     * Determines if this is a debug/fix request based on message content.
     * NOTE: Both debug and generation now use the same reference guide.
     */
    public boolean isDebugRequest(List<Map<String, String>> messages) {
        String lastUserMsg = messages.stream()
                .filter(m -> "user".equals(m.get("role")))
                .reduce((first, second) -> second)
                .map(m -> m.get("content").toLowerCase())
                .orElse("");

        // Keywords indicating debug/fix operations
        return lastUserMsg.contains("fix") || lastUserMsg.contains("error") ||
               lastUserMsg.contains("debug") || lastUserMsg.contains("oprav") ||
               lastUserMsg.contains("chyb");
    }

    /**
     * Builds the retrieval query from conversation history.
     * Short follow-up messages (fewer than 10 words) use the last 3 user messages
     * to provide better retrieval context.
     */
    public String buildQuery(List<Map<String, String>> messages) {
        List<Map<String, String>> userMsgs = messages.stream()
                .filter(m -> "user".equals(m.get("role")))
                .collect(Collectors.toList());

        if (userMsgs.isEmpty()) return "";

        String lastMsg = userMsgs.get(userMsgs.size() - 1).get("content");
        if (lastMsg.split("\\s+").length < 10 && userMsgs.size() > 1) {
            // Short follow-up — use last 3 messages for better retrieval
            int from = Math.max(0, userMsgs.size() - 3);
            return userMsgs.subList(from, userMsgs.size())
                    .stream().map(m -> m.get("content")).collect(Collectors.joining(" "));
        }
        return lastMsg;
    }

    /**
     * Retrieve RAG context for the current message history.
     * Returns the assembled guide sections string ready for injection.
     */
    public String retrieveContext(List<Map<String, String>> messages) throws Exception {
        String query = buildQuery(messages);
        return ragService.retrieve(query);
    }

    /**
     * Injects the retrieved context as a prefix into the first user message.
     * Works for all providers (Claude RAG, OpenAI, Gemini).
     */
    public List<Map<String, String>> injectContextIntoMessages(
            List<Map<String, String>> messages, String context) {

        java.util.List<Map<String, String>> enriched = new java.util.ArrayList<>();
        boolean injected = false;

        for (Map<String, String> msg : messages) {
            if (!injected && "user".equals(msg.get("role"))) {
                Map<String, String> enrichedMsg = new java.util.HashMap<>(msg);
                enrichedMsg.put("content",
                        "[PETRIFLOW GUIDE — RELEVANT SECTIONS]\n\n" + context +
                        "\n\n[END OF GUIDE CONTEXT]\n\n---\n\n" + msg.get("content"));
                enriched.add(enrichedMsg);
                injected = true;
            } else {
                enriched.add(msg);
            }
        }
        return enriched;
    }

    public static final String SYSTEM_PROMPT =
        "You are a Petriflow XML generation expert. " +
        "The Petriflow guide is provided as context in this conversation. " +
        "Follow every rule in the guide exactly. " +
        "Output XML in a single fenced ```xml code block, properly indented, complete, no stubs. " +
        "Always ask ALL clarifying questions in ONE message before generating XML. " +
        "Format your clarifying questions using markdown: use ### for headings, numbered lists for questions, and **bold** for emphasis.";
}
