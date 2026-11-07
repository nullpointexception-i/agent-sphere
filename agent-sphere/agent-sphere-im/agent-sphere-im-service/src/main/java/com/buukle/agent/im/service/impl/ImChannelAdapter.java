package com.buukle.agent.im.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.buukle.agent.channel.spi.ChannelAdapter;
import com.buukle.agent.channel.spi.InboundRequest;
import com.buukle.agent.channel.spi.NormalizedEvent;
import com.buukle.agent.channel.spi.OutboundFormat;
import com.buukle.agent.channel.spi.OutboundMessage;
import com.buukle.agent.channel.spi.SendResult;
import com.buukle.agent.channel.spi.TaskCardPayload;
import com.buukle.agent.common.context.TenantUtil;
import com.buukle.agent.im.domain.ImConversation;
import com.buukle.agent.im.dtvo.vo.ImTextContentVO;
import com.buukle.agent.im.dtvo.vo.MessageVO;
import com.buukle.agent.im.repository.ImConversationMapper;
import com.buukle.agent.im.service.ImMessageService;
import com.buukle.agent.util.json.JsonUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Built-in IM channel adapter.
 *
 * <p>Outbound locates the conversation via {@code agent_thread.external_thread_id}
 * (= im_conversation.id) and writes im_message as AGENT; inbound is handled directly by IM's own
 * Service, so this adapter does not parse it again.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ImChannelAdapter implements ChannelAdapter {

    public static final String TYPE = "IM_BUILTIN";
    private static final int MAX_CLIENT_MSG_ID = 64;

    private final ImConversationMapper conversationMapper;
    private final ImMessageService messageService;

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public List<NormalizedEvent> parse(InboundRequest request) {
        return List.of();
    }

    @Override
    public SendResult send(OutboundMessage message) {
        if (message == null || message.threadId() == null) {
            return SendResult.failed("threadId is required");
        }
        ImConversation conversation = findByThread(message.threadId());
        if (conversation == null) {
            return SendResult.failed("conversation not found for thread " + message.threadId());
        }
        String clientMsgId = truncate(message.idempotencyKey(), MAX_CLIENT_MSG_ID);
        String type = messageType(message);
        Object content = contentOf(type, message.text());
        try {
            MessageVO sent = messageService.sendAsAgent(
                    conversation.getId(), message.superAgentId(), type, content, clientMsgId);
            return SendResult.ok(sent == null || sent.getId() == null ? null
                    : String.valueOf(sent.getId()));
        } catch (Exception e) {
            log.warn("IM outbound failed: thread={}, error={}", message.threadId(), e.getMessage());
            return SendResult.failed(e.getMessage());
        }
    }

    @Override
    public SendResult update(OutboundMessage message) {
        if (message == null || message.targetMessageId() == null || message.threadId() == null) {
            return SendResult.failed("targetMessageId and threadId are required");
        }
        ImConversation conversation = findByThread(message.threadId());
        if (conversation == null) {
            return SendResult.failed("conversation not found for thread " + message.threadId());
        }
        String type = messageType(message);
        try {
            messageService.updateAsAgent(conversation.getId(), message.targetMessageId(),
                    type, contentOf(type, message.text()));
            return SendResult.ok(String.valueOf(message.targetMessageId()));
        } catch (Exception e) {
            log.warn("IM message update failed: thread={}, message={}, error={}",
                    message.threadId(), message.targetMessageId(), e.getMessage());
            return SendResult.failed(e.getMessage());
        }
    }

    private ImConversation findByThread(Long threadId) {
        String tenant = TenantUtil.get();
        TenantUtil.stop();
        try {
            return conversationMapper.selectOne(Wrappers.<ImConversation>lambdaQuery()
                    .eq(ImConversation::getThreadId, threadId)
                    .last("LIMIT 1"));
        } finally {
            if (tenant != null && !tenant.isBlank()) {
                TenantUtil.start(tenant);
            }
        }
    }

    private static String messageType(OutboundMessage message) {
        return message.format() == null || message.format().isBlank()
                ? OutboundFormat.TEXT : message.format().toUpperCase();
    }

    private static Object contentOf(String type, String text) {
        return OutboundFormat.CARD.equals(type) ? parseCard(text)
                : new ImTextContentVO(text == null ? "" : text);
    }

    private static TaskCardPayload parseCard(String text) {
        if (text == null || text.isBlank()) {
            return new TaskCardPayload();
        }
        TaskCardPayload card = JsonUtils.parse(text, TaskCardPayload.class);
        if (card != null) {
            return card;
        }
        TaskCardPayload fallback = new TaskCardPayload();
        fallback.setSummary(text);
        return fallback;
    }

    private static String truncate(String text, int max) {
        if (text == null) {
            return null;
        }
        return text.length() <= max ? text : text.substring(0, max);
    }
}
