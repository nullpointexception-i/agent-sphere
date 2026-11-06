package com.buukle.agent.im.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.buukle.agent.channel.spi.EventIngestPort;
import com.buukle.agent.channel.spi.NormalizedEvent;
import com.buukle.agent.common.constant.ActorType;
import com.buukle.agent.common.context.AuthContext;
import com.buukle.agent.common.context.TenantUtil;
import com.buukle.agent.common.error.CommonErrorCode;
import com.buukle.agent.common.exception.BizException;
import com.buukle.agent.common.metrics.AgentMetrics;
import com.buukle.agent.im.domain.ImConversation;
import com.buukle.agent.im.domain.ImMessage;
import com.buukle.agent.im.domain.ImParticipant;
import com.buukle.agent.im.domain.ImReadCursor;
import com.buukle.agent.im.dtvo.dto.SendMessageDTO;
import com.buukle.agent.im.dtvo.dto.ImGateConfig;
import com.buukle.agent.im.dtvo.enums.ImMessageStatus;
import com.buukle.agent.im.dtvo.enums.ImMessageType;
import com.buukle.agent.im.dtvo.enums.ImParticipantType;
import com.buukle.agent.im.dtvo.enums.ImConversationType;
import com.buukle.agent.im.dtvo.enums.ImStreamEventType;
import com.buukle.agent.im.dtvo.vo.ImEventMetaVO;
import com.buukle.agent.im.dtvo.vo.ImMessagePageVO;
import com.buukle.agent.im.dtvo.vo.ImStreamEvent;
import com.buukle.agent.im.dtvo.vo.ImTextContentVO;
import com.buukle.agent.im.dtvo.vo.MessageVO;
import com.buukle.agent.im.repository.ImConversationMapper;
import com.buukle.agent.im.repository.ImConversationSettingMapper;
import com.buukle.agent.im.repository.ImMessageMapper;
import com.buukle.agent.im.repository.ImParticipantMapper;
import com.buukle.agent.im.repository.ImReadCursorMapper;
import com.buukle.agent.im.service.ImMessageService;
import com.buukle.agent.im.service.ImStreamService;
import com.buukle.agent.superagent.dtvo.enums.SuperAgentStatus;
import com.buukle.agent.superagent.spi.ChatAgentIdentity;
import com.buukle.agent.superagent.spi.SessionInputSpi;
import com.buukle.agent.superagent.spi.SuperAgentDirectorySpi;
import com.buukle.agent.util.json.JsonUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RAtomicLong;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class ImMessageServiceImpl implements ImMessageService {

    private static final DateTimeFormatter DTF = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final int DEFAULT_LIMIT = 30;
    private static final int MAX_LIMIT = 100;
    private static final int MAX_SEARCH_KEYWORD_CHARS = 100;
    private static final String DEFAULT_DEVICE = "default";
    private static final String CHANNEL_IM = "IM_BUILTIN";

    private final ImMessageMapper messageMapper;
    private final ImConversationMapper conversationMapper;
    private final ImConversationSettingMapper settingMapper;
    private final ImParticipantMapper participantMapper;
    private final ImReadCursorMapper readCursorMapper;
    private final ImStreamService streamService;
    private final ObjectProvider<EventIngestPort> ingestPortProvider;
    private final ObjectProvider<SuperAgentDirectorySpi> directoryPortProvider;
    private final ObjectProvider<SessionInputSpi> sessionInputPortProvider;
    private final AgentMetrics metrics;

    @org.springframework.beans.factory.annotation.Value("${buukle.agent.im.max-message-chars:8000}")
    private int maxMessageChars = 8000;
    @org.springframework.beans.factory.annotation.Value("${buukle.agent.im.rate-limit-per-minute:30}")
    private int rateLimitPerMinute = 30;

    @Autowired(required = false)
    private RedissonClient redissonClient;

    @Override
    @Transactional
    public MessageVO send(Long conversationId, SendMessageDTO dto) {
        Long userId = currentUserId();
        checkRateLimit(userId);
        ImConversation conversation = systemRead(() -> conversationMapper.selectById(conversationId));
        if (conversation == null) {
            throw new BizException(CommonErrorCode.PARAM_INVALID, "conversation not found");
        }
        assertMember(conversationId, userId);
        assertAgentChattable(conversation, userId);

        if (dto.getClientMsgId() != null && !dto.getClientMsgId().isBlank()) {
            ImMessage existing = messageMapper.selectOne(Wrappers.<ImMessage>lambdaQuery()
                    .eq(ImMessage::getConversationId, conversationId)
                    .eq(ImMessage::getClientMsgId, dto.getClientMsgId())
                    .last("LIMIT 1"));
            if (existing != null) {
                return toVO(existing);
            }
        }

        Long seq = conversationMapper.nextSeq(conversationId);
        if (seq == null) {
            throw new BizException(CommonErrorCode.PARAM_INVALID, "conversation not found");
        }
        String type = dto.getType() == null || dto.getType().isBlank()
                ? ImMessageType.TEXT : dto.getType().toUpperCase();
        String messageText = extractText(dto.getContent());
        if (messageText != null && messageText.length() > maxMessageChars) {
            throw new BizException(CommonErrorCode.PARAM_INVALID, "message is too long");
        }

        ImMessage message = new ImMessage();
        message.setConversationId(conversationId);
        message.setSeq(seq);
        message.setSenderType(ImParticipantType.USER);
        message.setSenderId(userId);
        message.setClientMsgId(dto.getClientMsgId());
        message.setType(type);
        message.setContent(JsonUtils.toJson(dto.getContent()));
        message.setReplyToId(dto.getReplyToId());
        message.setMentions(dto.getMentions() == null ? null : JsonUtils.toJson(dto.getMentions()));
        message.setStatus(ImMessageStatus.SENT);
        message.setActorType(ActorType.USER);
        message.setActorId(userId);
        try {
            messageMapper.insert(message);
        } catch (DuplicateKeyException e) {
            ImMessage existing = messageMapper.selectOne(Wrappers.<ImMessage>lambdaQuery()
                    .eq(ImMessage::getConversationId, conversationId)
                    .eq(ImMessage::getClientMsgId, dto.getClientMsgId())
                    .last("LIMIT 1"));
            if (existing != null) {
                return toVO(existing);
            }
            throw e;
        }

        LocalDateTime now = LocalDateTime.now();
        conversationMapper.updateLastMessage(conversationId, message.getId(), now);
        touchReadCursor(conversationId, userId, seq);

        publishToMembers(conversationId, message, userId);
        metrics.increment(AgentMetrics.IM_MESSAGE_SENT, "sender", "USER");
        if (answerPendingRouterClarification(conversation, message, dto)) {
            // The text resumed the parked Router run; keep it out of a fresh Router delivery.
            return toVO(message);
        }
        ingest(conversationId, message, dto);
        return toVO(message);
    }

    /**
     * Consumes the user's plain-text reply as the answer to a pending Router
     * clarification card, resuming the parked run. Fail-open: any error falls
     * back to the normal ingest path.
     */
    private boolean answerPendingRouterClarification(ImConversation conversation, ImMessage message,
                                                     SendMessageDTO dto) {
        if (conversation.getThreadId() == null
                || !ImConversationType.DM.equals(conversation.getType())
                || !ImMessageType.TEXT.equals(message.getType())) {
            return false;
        }
        Object content = parseJson(message.getContent());
        String text = content instanceof ImTextContentVO textContent ? textContent.getText()
                : extractText(content);
        if (text == null || text.isBlank()) {
            return false;
        }
        SessionInputSpi sessionInputSpi = sessionInputPortProvider.getIfAvailable();
        if (sessionInputSpi == null) {
            return false;
        }
        try {
            return sessionInputSpi.answerRouterClarification(conversation.getThreadId(), text);
        } catch (Exception e) {
            log.warn("Router clarification intercept failed: conversation={}, error={}",
                    conversation.getId(), e.getMessage());
            return false;
        }
    }

    @Override
    @Transactional
    public MessageVO sendAsAgent(Long conversationId, Long superAgentId, String text, String clientMsgId) {
        return sendAsAgent(conversationId, superAgentId, ImMessageType.TEXT,
                new ImTextContentVO(text == null ? "" : text), clientMsgId);
    }

    @Override
    @Transactional
    public MessageVO sendAsAgent(Long conversationId, Long superAgentId, String type,
                                 Object content, String clientMsgId) {
        ImConversation conversation = systemRead(() -> conversationMapper.selectById(conversationId));
        if (conversation == null) {
            throw new BizException(CommonErrorCode.PARAM_INVALID, "conversation not found");
        }
        if (clientMsgId != null && !clientMsgId.isBlank()) {
            ImMessage existing = systemRead(() -> messageMapper.selectOne(Wrappers.<ImMessage>lambdaQuery()
                    .eq(ImMessage::getConversationId, conversationId)
                    .eq(ImMessage::getClientMsgId, clientMsgId)
                    .last("LIMIT 1")));
            if (existing != null) {
                return toVO(existing);
            }
        }
        Long seq = conversationMapper.nextSeq(conversationId);
        if (seq == null) {
            throw new BizException(CommonErrorCode.PARAM_INVALID, "conversation not found");
        }
        ImMessage message = new ImMessage();
        message.setConversationId(conversationId);
        message.setSeq(seq);
        message.setSenderType(ImParticipantType.AGENT);
        message.setSenderId(superAgentId);
        message.setClientMsgId(clientMsgId);
        message.setType(type == null || type.isBlank() ? ImMessageType.TEXT : type.toUpperCase());
        message.setContent(JsonUtils.toJson(content));
        message.setStatus(ImMessageStatus.SENT);
        message.setActorType(ActorType.AGENT);
        message.setActorId(superAgentId);
        try {
            messageMapper.insert(message);
        } catch (DuplicateKeyException e) {
            ImMessage existing = systemRead(() -> messageMapper.selectOne(Wrappers.<ImMessage>lambdaQuery()
                    .eq(ImMessage::getConversationId, conversationId)
                    .eq(ImMessage::getClientMsgId, clientMsgId)
                    .last("LIMIT 1")));
            if (existing != null) {
                return toVO(existing);
            }
            throw e;
        }
        conversationMapper.updateLastMessage(conversationId, message.getId(), LocalDateTime.now());
        publishToMembers(conversationId, message, superAgentId);
        metrics.increment(AgentMetrics.IM_MESSAGE_SENT, "sender", "AGENT");
        return toVO(message);
    }

    @Override
    @Transactional
    public MessageVO updateAsAgent(Long conversationId, Long messageId, String type, Object content) {
        ImMessage message = systemRead(() -> messageMapper.selectById(messageId));
        if (message == null || !conversationId.equals(message.getConversationId())) {
            throw new BizException(CommonErrorCode.PARAM_INVALID, "message not found");
        }
        if (!ImParticipantType.AGENT.equals(message.getSenderType())) {
            throw new BizException(CommonErrorCode.FORBIDDEN, "only agent messages can be updated");
        }
        message.setType(type == null || type.isBlank() ? ImMessageType.TEXT : type.toUpperCase());
        message.setContent(JsonUtils.toJson(content));
        messageMapper.updateById(message);
        publishToMembers(conversationId, message, message.getSenderId(), ImStreamEventType.MESSAGE_UPDATED);
        return toVO(message);
    }

    @Override
    @Transactional
    public MessageVO edit(Long conversationId, Long messageId, Object content) {
        Long userId = currentUserId();
        ImMessage message = requireOwnMessage(conversationId, messageId, userId);
        message.setContent(JsonUtils.toJson(content));
        message.setStatus(ImMessageStatus.EDITED);
        messageMapper.updateById(message);
        publishToMembers(conversationId, message, userId, ImStreamEventType.MESSAGE_UPDATED);
        return toVO(message);
    }

    @Override
    @Transactional
    public void recall(Long conversationId, Long messageId) {
        Long userId = currentUserId();
        ImMessage message = requireOwnMessage(conversationId, messageId, userId);
        message.setStatus(ImMessageStatus.RECALLED);
        message.setContent(JsonUtils.toJson(new ImTextContentVO("")));
        messageMapper.updateById(message);
        publishToMembers(conversationId, message, userId, ImStreamEventType.MESSAGE_RECALLED);
    }

    private ImMessage requireOwnMessage(Long conversationId, Long messageId, Long userId) {
        ImMessage message = systemRead(() -> messageMapper.selectById(messageId));
        if (message == null || !conversationId.equals(message.getConversationId())) {
            throw new BizException(CommonErrorCode.PARAM_INVALID, "message not found");
        }
        if (!ImParticipantType.USER.equals(message.getSenderType())
                || !userId.equals(message.getSenderId())) {
            throw new BizException(CommonErrorCode.FORBIDDEN);
        }
        return message;
    }

    @Override
    public List<MessageVO> list(Long conversationId, Long beforeSeq, Long afterSeq, int limit) {
        return page(conversationId, beforeSeq, afterSeq, limit).getRows();
    }

    @Override
    public ImMessagePageVO page(Long conversationId, Long beforeSeq, Long afterSeq, int limit) {
        assertMember(conversationId, currentUserId());
        int size = normalizeLimit(limit);
        boolean ascending = afterSeq != null && afterSeq > 0;
        List<ImMessage> messages = fetch(conversationId, beforeSeq, afterSeq, size + 1, ascending);
        boolean hasMore = messages.size() > size;
        if (hasMore) {
            messages = new java.util.ArrayList<>(messages.subList(0, size));
        }
        if (!ascending) {
            messages = messages.reversed();
        }
        ImMessagePageVO page = new ImMessagePageVO();
        page.setRows(messages.stream().map(this::toVO).toList());
        page.setHasMore(hasMore);
        if (!messages.isEmpty()) {
            if (ascending) {
                page.setNextAfterSeq(messages.get(messages.size() - 1).getSeq());
            } else {
                page.setNextBeforeSeq(messages.get(0).getSeq());
            }
        }
        return page;
    }

    @Override
    public ImMessagePageVO search(Long conversationId, String keyword, Long beforeSeq, int limit) {
        assertMember(conversationId, currentUserId());
        if (keyword == null || keyword.isBlank()) {
            throw new BizException(CommonErrorCode.PARAM_INVALID, "keyword is required");
        }
        String trimmed = keyword.trim();
        String normalized = trimmed.length() > MAX_SEARCH_KEYWORD_CHARS
                ? trimmed.substring(0, MAX_SEARCH_KEYWORD_CHARS) : trimmed;
        int size = normalizeLimit(limit);
        List<ImMessage> messages = systemRead(() -> messageMapper.search(
                conversationId, normalized, beforeSeq, size + 1));
        boolean hasMore = messages.size() > size;
        if (hasMore) {
            messages = new java.util.ArrayList<>(messages.subList(0, size));
        }
        messages = messages.reversed();
        ImMessagePageVO page = new ImMessagePageVO();
        page.setRows(messages.stream().map(this::toVO).toList());
        page.setHasMore(hasMore);
        if (!messages.isEmpty()) {
            page.setNextBeforeSeq(messages.get(0).getSeq());
        }
        return page;
    }

    private List<ImMessage> fetch(Long conversationId, Long beforeSeq, Long afterSeq,
                                  int limit, boolean ascending) {
        var query = Wrappers.<ImMessage>lambdaQuery()
                .eq(ImMessage::getConversationId, conversationId);
        if (beforeSeq != null && beforeSeq > 0) {
            query.lt(ImMessage::getSeq, beforeSeq);
        }
        if (afterSeq != null && afterSeq > 0) {
            query.gt(ImMessage::getSeq, afterSeq);
        }
        if (ascending) {
            query.orderByAsc(ImMessage::getSeq);
        } else {
            query.orderByDesc(ImMessage::getSeq);
        }
        query.last("LIMIT " + limit);
        return systemRead(() -> messageMapper.selectList(query));
    }

    private static int normalizeLimit(int limit) {
        return limit <= 0 ? DEFAULT_LIMIT : Math.min(limit, MAX_LIMIT);
    }

    private void publishToMembers(Long conversationId, ImMessage message, Long senderId) {
        publishToMembers(conversationId, message, senderId, ImStreamEventType.MESSAGE_CREATED);
    }

    private void publishToMembers(Long conversationId, ImMessage message, Long senderId, String eventType) {
        if (ImStreamEventType.MESSAGE_CREATED.equals(eventType)) {
            settingMapper.unhideByConversation(conversationId);
        }
        ImStreamEvent event = ImStreamEvent.builder()
                .type(eventType)
                .conversationId(conversationId)
                .messageId(message.getId())
                .seq(message.getSeq())
                .senderType(message.getSenderType())
                .senderId(message.getSenderId())
                .clientMsgId(message.getClientMsgId())
                .timestamp(System.currentTimeMillis())
                .previewText(ImStreamEventType.MESSAGE_RECALLED.equals(eventType)
                        ? "[消息已撤回]" : previewText(message))
                .createdAt(message.getCreatedAt() == null ? null : message.getCreatedAt().format(DTF))
                .build();
        afterCommit(() -> deliverToMembers(conversationId, event));
    }

    private void deliverToMembers(Long conversationId, ImStreamEvent event) {
        // DataPermissionInterceptor injects created_by = current user into SELECTs.
        // Participant rows are stamped with the conversation creator, so a reply from
        // the other member would otherwise resolve an empty member list and emit no SSE.
        List<ImParticipant> members = systemRead(() -> participantMapper.selectList(
                Wrappers.<ImParticipant>lambdaQuery()
                        .eq(ImParticipant::getConversationId, conversationId)
                        .eq(ImParticipant::getParticipantType, ImParticipantType.USER)));
        for (ImParticipant member : members) {
            if (member.getParticipantId() != null) {
                streamService.publish(member.getParticipantId(), event);
            }
        }
    }

    /**
     * Publish after the surrounding transaction commits, otherwise clients can
     * receive message_created and query the conversation before it is visible.
     */
    private static void afterCommit(Runnable action) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    action.run();
                }
            });
        } else {
            action.run();
        }
    }

    private static String previewText(ImMessage message) {
        if (message.getContent() == null || message.getContent().isBlank()) {
            return "";
        }
        try {
            Object parsed = JsonUtils.parse(message.getContent(), Object.class);
            if (parsed instanceof Map<?, ?> map) {
                for (String key : List.of("text", "summary", "fileName", "title")) {
                    Object value = map.get(key);
                    if (value instanceof String text && !text.isBlank()) {
                        return text;
                    }
                }
            }
        } catch (Exception ignored) {
            // fall through to type label
        }
        if (ImMessageType.IMAGE.equals(message.getType())) {
            return "[图片]";
        }
        if (ImMessageType.FILE.equals(message.getType())) {
            return "[文件]";
        }
        if (ImMessageType.CARD.equals(message.getType())) {
            return "[卡片]";
        }
        if (ImMessageType.SYSTEM.equals(message.getType())) {
            return "[系统消息]";
        }
        return "";
    }

    private String conversationTitle(Long conversationId) {
        ImConversation conversation = systemRead(() -> conversationMapper.selectById(conversationId));
        return conversation == null ? "" : conversation.getTitle();
    }

    private String conversationPurpose(Long conversationId) {
        ImConversation conversation = systemRead(() -> conversationMapper.selectById(conversationId));
        return conversation == null ? "" : conversation.getPurpose();
    }

    private String conversationGateConfig(Long conversationId) {
        ImConversation conversation = systemRead(() -> conversationMapper.selectById(conversationId));
        return conversation == null ? "" : conversation.getGateConfig();
    }

    private String conversationType(Long conversationId) {
        ImConversation conversation = systemRead(() -> conversationMapper.selectById(conversationId));
        return conversation == null ? null : conversation.getType();
    }

    /** Paused avatars cannot receive new messages in their direct chats. */
    private void assertAgentChattable(ImConversation conversation, Long userId) {
        if (!"DM".equalsIgnoreCase(conversation.getType())) {
            return;
        }
        SuperAgentDirectorySpi directory = directoryPortProvider.getIfAvailable();
        if (directory == null) {
            return;
        }
        List<ImParticipant> agents = systemRead(() -> participantMapper.selectList(
                Wrappers.<ImParticipant>lambdaQuery()
                        .eq(ImParticipant::getConversationId, conversation.getId())
                        .eq(ImParticipant::getParticipantType, ImParticipantType.AGENT)));
        for (ImParticipant agent : agents) {
            ChatAgentIdentity identity = directory.resolveForChat(
                    agent.getParticipantId(), userId);
            if (identity != null && !SuperAgentStatus.ACTIVE.equals(identity.status())) {
                throw new BizException(CommonErrorCode.PARAM_INVALID,
                        "digital avatar is paused; resume it before chatting");
            }
        }
    }

    private boolean shouldIngest(Long conversationId, SendMessageDTO dto) {        ImConversation conversation = systemRead(() -> conversationMapper.selectById(conversationId));
        if (conversation == null || !"GROUP".equals(conversation.getType())) {
            return true;
        }
        String trigger = ImGateConfig.TRIGGER_MENTION;
        if (conversation.getGateConfig() != null && !conversation.getGateConfig().isBlank()) {
            try {
                ImGateConfig config = JsonUtils.parse(conversation.getGateConfig(), ImGateConfig.class);
                if (config != null && config.getTrigger() != null) {
                    trigger = config.getTrigger();
                }
            } catch (Exception ignored) {
                // Fall back to MENTION.
            }
        }
        if (!ImGateConfig.TRIGGER_MENTION.equalsIgnoreCase(trigger)) {
            return true;
        }
        if (dto.getMentions() == null) {
            return false;
        }
        return dto.getMentions().stream()
                .anyMatch(mention -> mention != null && mention.getType() != null
                        && ImParticipantType.AGENT.equalsIgnoreCase(mention.getType()));
    }

    private void ingest(Long conversationId, ImMessage message, SendMessageDTO dto) {        EventIngestPort port = ingestPortProvider.getIfAvailable();
        if (port == null) {
            return;
        }
        if (!shouldIngest(conversationId, dto)) {
            return;
        }
        try {
            List<Long> agentIds = participantMapper.selectList(Wrappers.<ImParticipant>lambdaQuery()
                            .eq(ImParticipant::getConversationId, conversationId)
                            .eq(ImParticipant::getParticipantType, ImParticipantType.AGENT))
                    .stream().map(ImParticipant::getParticipantId).filter(java.util.Objects::nonNull).toList();
            var result = port.ingest(new NormalizedEvent(
                    CHANNEL_IM,
                    String.valueOf(conversationId),
                    String.valueOf(message.getId()),
                    "IM_MESSAGE",
                    String.valueOf(message.getSenderId()),
                    false,
                    extractText(dto.getContent()),
                    List.of(),
                    JsonUtils.toJson(eventMeta(message, dto, agentIds, conversationId)),
                    null
            ));
            if (result != null && result.threadId() != null) {
                conversationMapper.updateThreadId(conversationId, result.threadId());
            }
        } catch (Exception e) {
            // 事件入库失败不能阻断消息发送；后续可用 outbox/重试补偿
            log.warn("IM event ingest failed: conversation={}, message={}, error={}",
                    conversationId, message.getId(), e.getMessage());
        }
    }

    private ImEventMetaVO eventMeta(ImMessage message, SendMessageDTO dto,
                                    List<Long> agentIds, Long conversationId) {
        ImEventMetaVO meta = new ImEventMetaVO();
        meta.setType(message.getType());
        meta.setMentions(dto.getMentions() == null ? List.of() : dto.getMentions());
        meta.setAgentIds(agentIds);
        meta.setConversationType(conversationType(conversationId));
        meta.setConversationTitle(conversationTitle(conversationId));
        meta.setPurpose(conversationPurpose(conversationId));
        meta.setGateConfig(conversationGateConfig(conversationId));
        return meta;
    }

    private static String extractText(Object content) {
        if (content == null) {
            return "";
        }
        if (content instanceof String s) {
            return s;
        }
        if (content instanceof ImTextContentVO text) {
            return text.getText() == null ? "" : text.getText();
        }
        if (content instanceof Map<?, ?> map) {
            Object text = map.get("text");
            return text == null ? JsonUtils.toJson(content) : String.valueOf(text);
        }
        return JsonUtils.toJson(content);
    }

    private void touchReadCursor(Long conversationId, Long userId, Long seq) {
        ImReadCursor cursor = readCursorMapper.selectOne(Wrappers.<ImReadCursor>lambdaQuery()
                .eq(ImReadCursor::getConversationId, conversationId)
                .eq(ImReadCursor::getUserId, userId)
                .eq(ImReadCursor::getDeviceId, DEFAULT_DEVICE)
                .last("LIMIT 1"));
        if (cursor == null) {
            cursor = new ImReadCursor();
            cursor.setConversationId(conversationId);
            cursor.setUserId(userId);
            cursor.setDeviceId(DEFAULT_DEVICE);
            cursor.setLastReadSeq(seq);
            readCursorMapper.insert(cursor);
        } else if (cursor.getLastReadSeq() == null || cursor.getLastReadSeq() < seq) {
            cursor.setLastReadSeq(seq);
            readCursorMapper.updateById(cursor);
        }
    }

    /**
     * Agent 消息由后台 Outbound 写入（created_by=system），用户读取时必须绕过
     * created_by 数据权限；成员校验在调用前已完成。
     */
    private void checkRateLimit(Long userId) {
        if (redissonClient == null || rateLimitPerMinute <= 0) {
            return;
        }
        long minute = System.currentTimeMillis() / 60000;
        String key = "im:rate:" + userId + ":" + minute;
        RAtomicLong counter = redissonClient.getAtomicLong(key);
        long value = counter.incrementAndGet();
        if (value == 1) {
            counter.expire(java.time.Duration.ofMinutes(2));
        }
        if (value > rateLimitPerMinute) {
            throw new BizException(CommonErrorCode.PARAM_INVALID, "too many messages");
        }
    }

    private <T> T systemRead(java.util.function.Supplier<T> supplier) {
        String tenant = TenantUtil.get();
        boolean admin = AuthContext.isSuperAdmin();
        TenantUtil.stop();
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

    private MessageVO toVO(ImMessage message) {
        MessageVO vo = new MessageVO();
        vo.setId(message.getId());
        vo.setConversationId(message.getConversationId());
        vo.setSeq(message.getSeq());
        vo.setSenderType(message.getSenderType());
        vo.setSenderId(message.getSenderId());
        vo.setClientMsgId(message.getClientMsgId());
        vo.setType(message.getType());
        vo.setContent(parseJson(message.getContent()));
        vo.setReplyToId(message.getReplyToId());
        vo.setMentions(parseJson(message.getMentions()));
        vo.setStatus(message.getStatus());
        vo.setCreatedAt(message.getCreatedAt() == null ? null : message.getCreatedAt().format(DTF));
        return vo;
    }

    private static Object parseJson(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return JsonUtils.parse(json, Object.class);
        } catch (Exception e) {
            return json;
        }
    }

    private void assertMember(Long conversationId, Long userId) {
        long count = systemRead(() -> participantMapper.selectCount(Wrappers.<ImParticipant>lambdaQuery()
                .eq(ImParticipant::getConversationId, conversationId)
                .eq(ImParticipant::getParticipantType, ImParticipantType.USER)
                .eq(ImParticipant::getParticipantId, userId)));
        if (count == 0) {
            throw new BizException(CommonErrorCode.FORBIDDEN);
        }
    }

    private static Long currentUserId() {
        Long userId = AuthContext.getUserId();
        if (userId == null) {
            throw new BizException(CommonErrorCode.UNAUTHORIZED);
        }
        return userId;
    }
}
