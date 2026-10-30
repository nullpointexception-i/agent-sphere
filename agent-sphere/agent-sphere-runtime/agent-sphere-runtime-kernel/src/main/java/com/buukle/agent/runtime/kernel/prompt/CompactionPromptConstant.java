package com.buukle.agent.runtime.kernel.prompt;

public final class CompactionPromptConstant {
    private static final String BASE_PROMPT =
            "You are a conversation summarizer for an AI agent platform. Produce a structured summary in this exact format:\n\n"
                    + "## Goal\n"
                    + "(What is the user's overall objective?)\n\n"
                    + "## Progress\n"
                    + "(What has been accomplished so far?)\n\n"
                    + "## Decisions\n"
                    + "(What key decisions or tradeoffs were made?)\n\n"
                    + "## Next Steps\n"
                    + "(What remains to be done?)\n\n"
                    + "## Key Context\n"
                    + "(Critical details the agent needs to remember: file paths, environment specifics, preferences, error messages, etc.)\n\n"
                    + "Rules:\n"
                    + "- Follow the format exactly. Each section must be present, even if empty.\n"
                    + "- Keep key facts, user intents, decisions, and important context.\n"
                    + "- Omit greetings, farewells, and trivial exchanges.\n"
                    + "- Write in past tense, be concise.\n"
                    + "- Preserve specific details like model names, error messages, configuration values.\n"
                    + "- If updating an existing summary, incorporate new information without repeating what's already captured.\n"
                    + "- NEVER output raw JSON, code blocks, long tables, or verbatim tool payloads; summarize in prose.\n"
                    + "- The token limit is a HARD limit: finish every section and end with a complete sentence.\n"
                    + "  Never leave an unterminated code fence, JSON object/array, or quote. If space is tight,\n"
                    + "  prioritize Goal / Progress / Next Steps / Key Context and stop cleanly.\n\n"
                    + "Now process the following. Stay within the token limit and produce a COMPLETE summary:\n";


    /** 摘要超过目标预算时，把上一轮摘要作为输入再压缩。 */
    public static String buildReducePrompt(String existingSummary, long maxSummaryTokens) {
        return "You are compressing an existing conversation summary. Output ONLY the compressed summary, no extra text.\n\n"
                + "Keep the exact section format: ## Goal, ## Progress, ## Decisions, ## Next Steps, ## Key Context.\n"
                + "Preserve the newest facts, decisions, file paths, configuration values, and next steps; compress older details first.\n"
                + "Do not add new facts. Do not output raw JSON, code blocks, or long tables.\n"
                + "Your summary MUST NOT exceed " + maxSummaryTokens + " tokens. This is a hard limit: finish every section "
                + "and end with a complete sentence. Never leave an unterminated code fence, JSON object/array, or quote.\n\n"
                + "Existing summary:\n" + existingSummary;
    }

    private CompactionPromptConstant() {
    }

    public static String getSystemPrompt() {
        return "You are a conversation summarizer. Output ONLY the updated summary, no extra text, no prefixes.";
    }

    public static String buildPrompt(String existingSummary, String messagesText, long maxSummaryTokens) {
        StringBuilder sb = new StringBuilder(BASE_PROMPT);
        sb.append("\nYour summary MUST NOT exceed ").append(maxSummaryTokens)
                .append(" tokens. This is a hard limit: finish all sections and end cleanly; ")
                .append("do not leave an unterminated code fence, JSON, or quote.\n\n");
        if (existingSummary != null && !existingSummary.isBlank()) {
            sb.append("Existing summary:\n").append(existingSummary).append("\n\n");
        }
        sb.append("Messages:\n").append(messagesText);
        return sb.toString();
    }
}
