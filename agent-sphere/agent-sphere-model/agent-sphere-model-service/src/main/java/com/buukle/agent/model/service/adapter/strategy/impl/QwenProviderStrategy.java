package com.buukle.agent.model.service.adapter.strategy.impl;

import com.buukle.agent.common.constant.ReasoningEffort;
import com.buukle.agent.model.dtvo.dto.complete.ChatCompletionRequestDTO;
import com.buukle.agent.model.dtvo.dto.complete.ChatMessageDTO;
import com.buukle.agent.model.dtvo.enums.ModelProviderCompany;
import com.buukle.agent.model.service.adapter.record.ContentToolCallResult;
import com.buukle.agent.model.service.adapter.strategy.ProviderStrategy;
import com.buukle.agent.model.service.constants.LlmApiConstants;
import com.buukle.agent.model.service.stream.ToolStream;
import com.buukle.agent.util.json.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Qwen (Tongyi Qianwen) strategy.
 *
 * <p>Qwen3 deployments (vLLM / PAI-EAS gateways) apply **strict** schema validation to the request:
 * the assistant message fields {@code content} and {@code reasoning_content} are both "key must exist"
 * fields (the value may be an empty string). But {@code ChatMessageDTO} is class-level
 * {@code @JsonInclude(NON_NULL)} — for an assistant message that only carries tool_calls, content is
 * null, so the whole key is omitted during serialization and the gateway returns 400 directly:
 *
 * <pre>
 * {'type':'missing','loc':('body','messages',9,...,'content'),'msg':'Field required'}
 * </pre>
 *
 * <p><b>This strategy may only fill in an empty string, never null</b>: NON_NULL would omit null again,
 * so setting null changes nothing.
 */
@Component
public class QwenProviderStrategy implements ProviderStrategy {

    private static final String ROLE_ASSISTANT = "assistant";
    private static final String ROLE_SYSTEM = "system";
    private static final String ROLE_USER = "user";

    @Override
    public ModelProviderCompany getCompany() {
        return ModelProviderCompany.QWEN;
    }

    @Override
    public void adaptRequest(ChatCompletionRequestDTO request) {
        // Qwen3 is an OpenAI-compatible API; thinking / tool_stream are internal fields this system uses
        // for Zhipu/DeepSeek.
        // Qwen's thinking switch is enable_thinking and its thinking budget is thinking_budget; the unified
        // effort tier goes in reasoning_effort.
        request.setThinking(null);
        request.setToolStream(null);
        if (request.getReasoningEffort() != null) {
            boolean off = ReasoningEffort.NONE.equalsIgnoreCase(request.getReasoningEffort());
            request.setEnableThinking(!off);
            if (off) {
                request.setThinkingBudget(null);
            }
        }
        request.setReasoningEffort(null);

        List<ChatMessageDTO> messages = request.getMessages();
        if (messages == null) {
            return;
        }
        for (ChatMessageDTO m : messages) {
            if (m == null || !ROLE_ASSISTANT.equals(m.getRole())) {
                continue;
            }
            boolean hasToolCalls = m.getToolCalls() != null && !m.getToolCalls().isEmpty();
            if (!hasToolCalls) {
                continue;
            }
            // The content key must exist (an empty string suffices; null is omitted by NON_NULL).
            if (m.getContent() == null) {
                m.setContent("");
            }
            // When thinking is enabled in Qwen3, multi-turn tool calling requires the assistant's
            // reasoning_content to be sent back.
            // The message layer has no access to the original reasoning text, so this only guarantees the key
            // exists — an empty string lets the template read it normally.
            if (m.getReasoningContent() == null) {
                m.setReasoningContent("");
            }
        }

        // The Qwen3 template requires all system messages to come first, and the gateway only recognizes messages[0]:
        //   "System message must be at the beginning."
        // Two sources must be handled:
        //   1) leading system: the runner's system prompt + the "previous turn cancelled" / "compaction summary"
        //      injected by HistoryLoader
        //      → they must be merged into one, otherwise a second leading system also returns 400;
        //   2) trailing system: current time / todo / loop budget / silent nudge / finalization
        //      → demoted to user (keeping position and order), avoiding moving per-turn content to messages[0]
        //      (which preserves the prefix cache).
        List<ChatMessageDTO> normalized = demoteTrailingSystem(mergeLeadingSystem(messages));
        if (normalized != messages) {
            request.setMessages(normalized);
        }
    }

    /**
     * Merge consecutive leading system messages into a single one (preserving order, separated by blank lines).
     *
     * <p>The Qwen template only reads {@code messages[0]} as system, and the gateway requires that system must
     * not appear at any non-zero position; HistoryLoader inserts a system prompt at the very front of history
     * when "the previous turn was cancelled" or "a compaction summary exists", forming multiple leading systems
     * together with the runner's system prompt — only after merging does the validation pass.
     *
     * <p>When there is no more than one leading system, return as-is (fast path, list reference unchanged).
     */
    private static List<ChatMessageDTO> mergeLeadingSystem(List<ChatMessageDTO> messages) {
        int leading = 0;
        while (leading < messages.size()
                && messages.get(leading) != null
                && ROLE_SYSTEM.equals(messages.get(leading).getRole())) {
            leading++;
        }
        if (leading <= 1) {
            return messages;
        }
        StringBuilder merged = new StringBuilder();
        for (int i = 0; i < leading; i++) {
            Object content = messages.get(i).getContent();
            String text = content instanceof String s
                    ? s
                    : (content == null ? null : JsonUtils.toJson(content));
            if (text == null || text.isBlank()) {
                continue;
            }
            if (merged.length() > 0) {
                merged.append("\n\n");
            }
            merged.append(text);
        }
        List<ChatMessageDTO> out = new ArrayList<>(messages.size() - leading + 1);
        out.add(new ChatMessageDTO().setRole(ROLE_SYSTEM).setContent(merged.toString()));
        out.addAll(messages.subList(leading, messages.size()));
        return out;
    }

    /**
     * Demote system messages that appear after the first non-system message to user.
     *
     * <p><b>Objects must be newly created instead of mutating the role in place</b>: the request's messages are
     * a shallow copy of the runner list (new list, shared elements), so changing the role in place would pollute
     * the runner's history messages.
     */
    private static List<ChatMessageDTO> demoteTrailingSystem(List<ChatMessageDTO> messages) {
        boolean seenNonSystem = false;
        boolean changed = false;
        List<ChatMessageDTO> out = new ArrayList<>(messages.size());
        for (ChatMessageDTO m : messages) {
            if (m == null) {
                out.add(null);
                continue;
            }
            if (ROLE_SYSTEM.equals(m.getRole())) {
                if (seenNonSystem) {
                    out.add(asUser(m));
                    changed = true;
                    continue;
                }
                out.add(m);
                continue;
            }
            seenNonSystem = true;
            out.add(m);
        }
        return changed ? out : messages;
    }

    /** Keep only role/content/name for system messages; do not copy tool_calls and other fields to avoid polluting user messages. */
    private static ChatMessageDTO asUser(ChatMessageDTO source) {
        return new ChatMessageDTO()
                .setRole(ROLE_USER)
                .setContent(source.getContent())
                .setName(source.getName());
    }

    @Override
    public void adaptResponse(JsonNode choice, ToolStream toolStream) {
        parseDeltaToolCalls(choice, toolStream);
    }

    @Override
    public ContentToolCallResult extractToolCallsFromContent(String content) {
        return ContentToolCallResult.EMPTY;
    }

    /** Consistent with OpenAI-compatible streaming parsing (Qwen3's SSE delta structure is the same). */
    private void parseDeltaToolCalls(JsonNode choice, ToolStream toolStream) {
        if (!choice.has(LlmApiConstants.FIELD_DELTA)) {
            return;
        }
        JsonNode delta = choice.get(LlmApiConstants.FIELD_DELTA);
        if (delta.has(LlmApiConstants.FIELD_TOOL_CALLS)
                && delta.get(LlmApiConstants.FIELD_TOOL_CALLS).isArray()) {
            for (JsonNode tc : delta.get(LlmApiConstants.FIELD_TOOL_CALLS)) {
                int index = tc.has(LlmApiConstants.FIELD_INDEX)
                        ? tc.get(LlmApiConstants.FIELD_INDEX).asInt() : 0;
                String tcId = tc.has(LlmApiConstants.FIELD_ID)
                        ? tc.get(LlmApiConstants.FIELD_ID).asText() : null;
                String tcName = null;
                String tcArgs = null;
                if (tc.has(LlmApiConstants.FIELD_FUNCTION)) {
                    JsonNode fn = tc.get(LlmApiConstants.FIELD_FUNCTION);
                    if (fn.has(LlmApiConstants.FIELD_NAME) && !fn.get(LlmApiConstants.FIELD_NAME).isNull()) {
                        tcName = fn.get(LlmApiConstants.FIELD_NAME).asText();
                    }
                    if (fn.has(LlmApiConstants.FIELD_ARGUMENTS)) {
                        tcArgs = fn.get(LlmApiConstants.FIELD_ARGUMENTS).asText();
                    }
                }
                toolStream.onDelta(index, tcId, tcName, tcArgs);
            }
        }
    }
}
