package com.buukle.agent.im.service.impl;

import com.buukle.agent.common.context.TenantScopeHolder;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.buukle.agent.channel.spi.AgentStreamEvent;
import com.buukle.agent.channel.spi.AgentStreamPort;
import com.buukle.agent.common.context.AuthContext;
import com.buukle.agent.common.context.TenantUtil;
import com.buukle.agent.im.domain.ImConversation;
import com.buukle.agent.im.domain.ImParticipant;
import com.buukle.agent.im.dtvo.enums.ImParticipantType;
import com.buukle.agent.im.dtvo.enums.ImStreamEventType;
import com.buukle.agent.im.dtvo.vo.ImStreamEvent;
import com.buukle.agent.im.repository.ImConversationMapper;
import com.buukle.agent.im.repository.ImParticipantMapper;
import com.buukle.agent.im.service.ImStreamService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Relays ephemeral Agent streaming fragments to conversation members over the
 * user-scoped IM SSE stream. Display-only: it never writes {@code im_message}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ImAgentStreamPublisher implements AgentStreamPort {

    private static final long TARGET_TTL_MS = 300_000L;
    private static final int MAX_TARGET_CACHE = 2_000;

    private final ImConversationMapper conversationMapper;
    private final ImParticipantMapper participantMapper;
    private final ImStreamService streamService;
    private final Map<Long, CachedTarget> targets = new ConcurrentHashMap<>();

    @Override
    public void publish(AgentStreamEvent event) {
        if (event == null || event.threadId() == null) {
            return;
        }
        ConversationTarget target = resolveTarget(event.threadId());
        if (target == null || target.userIds().isEmpty()) {
            return;
        }
        ImStreamEvent streamEvent = ImStreamEvent.builder()
                .type(ImStreamEventType.AGENT_STREAM)
                .conversationId(target.conversationId())
                .threadId(event.threadId())
                .runId(event.runId())
                .agentId(event.agentId())
                .phase(event.phase())
                .delta(event.delta())
                .streamStatus(event.streamStatus())
                .toolName(event.toolName())
                .displayName(event.displayName())
                .errorMessage(event.errorMessage())
                .streamSeq(event.streamSeq())
                .timestamp(event.timestamp() == null ? System.currentTimeMillis() : event.timestamp())
                .build();
        try {
            for (Long userId : target.userIds()) {
                streamService.publish(userId, streamEvent);
            }
        } catch (Exception e) {
            log.debug("Agent stream publish failed: thread={}, conversation={}, error={}",
                    event.threadId(), target.conversationId(), e.getMessage());
        }
    }

    /** Conversation id + user participants for a thread, cached briefly (lookup per flush). */
    private ConversationTarget resolveTarget(Long threadId) {
        CachedTarget cached = targets.get(threadId);
        long now = System.currentTimeMillis();
        if (cached != null && cached.expiresAt > now) {
            return cached.target;
        }
        ConversationTarget resolved = systemRead(() -> {
            ImConversation conversation = conversationMapper.selectOne(
                    Wrappers.<ImConversation>lambdaQuery()
                            .eq(ImConversation::getThreadId, threadId)
                            .last("LIMIT 1"));
            if (conversation == null) {
                return null;
            }
            List<ImParticipant> members = participantMapper.selectList(
                    Wrappers.<ImParticipant>lambdaQuery()
                            .eq(ImParticipant::getConversationId, conversation.getId())
                            .eq(ImParticipant::getParticipantType, ImParticipantType.USER));
            List<Long> userIds = members.stream()
                    .map(ImParticipant::getParticipantId)
                    .filter(id -> id != null)
                    .distinct()
                    .toList();
            return new ConversationTarget(conversation.getId(), userIds);
        });
        if (resolved == null) {
            // Negative cache to avoid hammering the DB for unknown threads.
            targets.put(threadId, new CachedTarget(null, now + TARGET_TTL_MS));
            return null;
        }
        if (targets.size() >= MAX_TARGET_CACHE) {
            targets.clear();
        }
        targets.put(threadId, new CachedTarget(resolved, now + TARGET_TTL_MS));
        return resolved;
    }

    private <T> T systemRead(java.util.function.Supplier<T> supplier) {
        String tenant = TenantUtil.get();
        boolean admin = AuthContext.isSuperAdmin();
        TenantUtil.stop();
        TenantScopeHolder.clear();
        AuthContext.setSuperAdmin(true);
        try {
            return supplier.get();
        } finally {
            AuthContext.setSuperAdmin(admin);
            if (tenant != null) {
                TenantUtil.start(tenant);
            }
        }
    }

    private record ConversationTarget(Long conversationId, List<Long> userIds) {
    }

    private record CachedTarget(ConversationTarget target, long expiresAt) {
    }
}
