package com.petriflow.backend;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.FileWriter;
import java.io.PrintWriter;
import java.time.Instant;

/**
 * Appends one JSON line per generation to run-log.jsonl.
 * Fields: timestamp, llm, model, contextMode, embedProvider,
 *         promptTokens, outputTokens, cacheReadTokens, cacheWriteTokens,
 *         durationMs, estimatedCostUsd, truncated, userPrompt (first 200 chars), stopReason
 *
 * Load in Excel / Python / any JSONL reader for analysis across test runs.
 */
@Component
public class RunLogger {

    private static final Logger log = LoggerFactory.getLogger(RunLogger.class);
    private final ObjectMapper mapper = new ObjectMapper();

    @Autowired
    private AppConfig config;

    public static class RunEntry {
        public String timestamp       = Instant.now().toString();
        public String llm;
        public String model;
        public String contextMode;
        public String embedProvider;
        public long   promptTokens    = 0;
        public long   outputTokens    = 0;
        public long   cacheReadTokens = 0;
        public long   cacheWriteTokens= 0;
        public long   thinkingTokens  = 0;
        public long   durationMs      = 0;
        public double estimatedCostUsd= 0.0;
        public boolean truncated      = false;
        public boolean retry          = false;
        public String userPrompt      = "";
        public String stopReason      = "";
    }

    public void log(RunEntry entry) {
        // Fill provider info from config
        entry.llm          = config.llmProvider;
        entry.model        = config.activeLlmModel();
        entry.contextMode  = config.contextMode;
        entry.embedProvider= config.embedProvider;

        // Estimate cost (rough — update pricing as needed)
        entry.estimatedCostUsd = estimateCost(entry);

        try (PrintWriter pw = new PrintWriter(new FileWriter(config.runLogPath, true))) {
            pw.println(mapper.writeValueAsString(entry));
        } catch (Exception e) {
            log.warn("Failed to write run log: {}", e.getMessage());
        }

        String logMsg = entry.thinkingTokens > 0
            ? "Run logged: llm={} model={} mode={} prompt={}tok output={}tok thinking={}tok cache_read={}tok cache_write={}tok duration={}ms cost=${} stop={}"
            : "Run logged: llm={} model={} mode={} prompt={}tok output={}tok cache_read={}tok cache_write={}tok duration={}ms cost=${} stop={}";

        if (entry.thinkingTokens > 0) {
            log.info(logMsg,
                    entry.llm, entry.model, entry.contextMode,
                    entry.promptTokens, entry.outputTokens, entry.thinkingTokens,
                    entry.cacheReadTokens, entry.cacheWriteTokens,
                    entry.durationMs,
                    String.format("%.4f", entry.estimatedCostUsd),
                    entry.stopReason);
        } else {
            log.info(logMsg,
                    entry.llm, entry.model, entry.contextMode,
                    entry.promptTokens, entry.outputTokens,
                    entry.cacheReadTokens, entry.cacheWriteTokens,
                    entry.durationMs,
                    String.format("%.4f", entry.estimatedCostUsd),
                    entry.stopReason);
        }
    }

    /**
     * Approximate cost in USD based on published pricing (March 2025).
     * Update these when prices change.
     */
    private double estimateCost(RunEntry e) {
        double inputM, outputM, cacheReadM, cacheWriteM;
        switch (e.llm.toLowerCase()) {
            case "claude":
                // claude-sonnet-4-5 / claude-haiku-4-5 pricing
                if (e.model.contains("haiku")) {
                    inputM = 0.80; outputM = 4.00; cacheReadM = 0.08; cacheWriteM = 1.00;
                } else if (e.model.contains("opus")) {
                    inputM = 15.0; outputM = 75.0; cacheReadM = 1.50; cacheWriteM = 18.75;
                } else { // sonnet
                    inputM = 3.00; outputM = 15.0; cacheReadM = 0.30; cacheWriteM = 3.75;
                }
                return (e.promptTokens    / 1_000_000.0 * inputM)
                     + (e.outputTokens    / 1_000_000.0 * outputM)
                     + (e.cacheReadTokens / 1_000_000.0 * cacheReadM)
                     + (e.cacheWriteTokens/ 1_000_000.0 * cacheWriteM);

            case "openai":
                // gpt-4o / gpt-4o-mini
                if (e.model.contains("mini")) {
                    inputM = 0.15; outputM = 0.60;
                } else if (e.model.contains("turbo")) {
                    inputM = 10.0; outputM = 30.0;
                } else { // gpt-4o
                    inputM = 2.50; outputM = 10.0;
                }
                return (e.promptTokens / 1_000_000.0 * inputM)
                     + (e.outputTokens / 1_000_000.0 * outputM);

            case "gemini":
                // gemini-2.5-flash, gemini-2.0-flash — roughly same tier
                if (e.model.contains("pro") || e.model.contains("1.5")) {
                    inputM = 1.25; outputM = 5.00;
                } else { // flash
                    inputM = 0.075; outputM = 0.30;
                }
                return (e.promptTokens / 1_000_000.0 * inputM)
                     + (e.outputTokens / 1_000_000.0 * outputM);

            default:
                return 0;
        }
    }
}
