package com.petriflow.backend;

import java.util.List;

/**
 * Helper class to build retry prompts when XML validation fails.
 * Used by all LLM services (Claude, OpenAI, Gemini) for consistent retry behavior.
 */
public class XmlRetryHelper {

    /**
     * Builds a retry prompt with the original user request, generated XML, and validation errors.
     * The LLM is instructed to fix ONLY the listed errors and return corrected XML.
     */
    public static String buildRetryPrompt(String originalPrompt, String generatedXml, List<String> errors) {
        StringBuilder sb = new StringBuilder();

        sb.append("The following Petriflow XML was generated but contains validation errors:\n\n");
        sb.append("```xml\n");
        sb.append(generatedXml);
        sb.append("\n```\n\n");

        sb.append("**Validation Errors Found:**\n");
        for (int i = 0; i < errors.size(); i++) {
            sb.append((i + 1)).append(". ").append(errors.get(i)).append("\n");
        }

        sb.append("\n**Instructions:**\n");
        sb.append("- Fix ONLY the validation errors listed above\n");
        sb.append("- Do NOT change anything else in the XML\n");
        sb.append("- Return the complete corrected XML in a single ```xml code block\n");
        sb.append("- Ensure the XML is properly indented and valid\n");

        return sb.toString();
    }

    /**
     * Extracts the user notification message to show when validation fails.
     */
    public static String buildUserNotification(int errorCount) {
        return String.format("\n\n⚠️ Validation detected %d error%s. Retrying with corrections...\n\n",
                errorCount, errorCount == 1 ? "" : "s");
    }
}
