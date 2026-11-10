package com.buukle.agent.im.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.buukle.agent.common.context.AgentContextSnapshot;
import com.buukle.agent.common.context.AuthContext;
import com.buukle.agent.channel.spi.TaskCardPayload;
import com.buukle.agent.common.error.CommonErrorCode;
import com.buukle.agent.common.exception.BizException;
import com.buukle.agent.im.domain.ImConversation;
import com.buukle.agent.im.domain.ImMessage;
import com.buukle.agent.im.domain.ImParticipant;
import com.buukle.agent.im.dtvo.dto.AnswerClarificationDTO;
import com.buukle.agent.im.dtvo.dto.ImGateConfig;
import com.buukle.agent.im.dtvo.enums.ImMessageType;
import com.buukle.agent.im.dtvo.enums.ImParticipantRole;
import com.buukle.agent.im.dtvo.enums.ImParticipantType;
import com.buukle.agent.im.dtvo.enums.ImStreamEventType;
import com.buukle.agent.im.dtvo.vo.ClarificationAnswerVO;
import com.buukle.agent.im.dtvo.vo.ImStreamEvent;
import com.buukle.agent.im.repository.ImConversationMapper;
import com.buukle.agent.im.repository.ImMessageMapper;
import com.buukle.agent.im.repository.ImParticipantMapper;
import com.buukle.agent.im.service.ImClarificationService;
import com.buukle.agent.im.service.ImStreamService;
import com.buukle.agent.superagent.dtvo.vo.SessionInputVO;
import com.buukle.agent.superagent.spi.ChatAgentIdentity;
import com.buukle.agent.superagent.spi.SessionInputSpi;
import com.buukle.agent.superagent.spi.SuperAgentDirectorySpi;
import com.buukle.agent.util.json.JsonUtils;
import com.fasterxml.jackson.core.type.TypeReference;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Answers a Router clarification strictly through its card. Ordinary chat text never consumes a
 * pending clarification; the card carries the target input id and the reply mode.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ImClarificationServiceImpl implements ImClarificationService {

    private static final DateTimeFormatter DTF =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final String STATUS_ANSWERED = "ANSWERED";
    private static final String STATUS_PENDING = "PENDING";
    private static final List<String> OPTION_TEXT_KEYS =
            List.of("label", "value", "text", "option");

    private final ImConversationMapper conversationMapper;
    private final ImMessageMapper messageMapper;
    private final ImParticipantMapper participantMapper;
    private final ImStreamService streamService;
    private final ObjectProvider<SessionInputSpi> sessionInputPortProvider;
    private final ObjectProvider<SuperAgentDirectorySpi> directoryPortProvider;

    @Override
    @Transactional
    public ClarificationAnswerVO answer(Long conversationId, String inputId,
                                        AnswerClarificationDTO dto) {
        if (conversationId == null || inputId == null || inputId.isBlank() || dto == null) {
            throw new BizException(CommonErrorCode.PARAM_INVALID, "clarification is required");
        }
        ImConversation conversation = conversationMapper.selectById(conversationId);
        if (conversation == null || conversation.getThreadId() == null) {
            throw new BizException(CommonErrorCode.PARAM_INVALID, "conversation not found");
        }
        Long userId = currentUserId();
        assertMember(conversationId, userId);
        SessionInputSpi spi = sessionInputPortProvider.getIfAvailable();
        if (spi == null) {
            throw new BizException(CommonErrorCode.PARAM_INVALID, "clarification service unavailable");
        }
        SessionInputVO input = withCallerContext(
                () -> spi.findPendingByInput(conversation.getThreadId(), inputId));
        if (input == null || !STATUS_PENDING.equals(input.getStatus())) {
            throw new BizException(CommonErrorCode.PARAM_INVALID,
                    "clarification is not pending or already answered");
        }
        assertCanAnswer(conversation, input, userId);

        List<String> options = parseOptions(input.getOptions());
        AnswerPayload payload = validatePayload(input, options, dto);
        String responder = AuthContext.getUsername();
        boolean answered = withCallerContext(() -> spi.answerRouterClarification(
                conversation.getThreadId(), inputId, payload.answerText(), responder));
        if (!answered) {
            throw new BizException(CommonErrorCode.PARAM_INVALID,
                    "clarification is already answered or the run could not resume");
        }
        String respondedAt = LocalDateTime.now().format(DTF);
        updateCard(conversationId, inputId, payload, responder, respondedAt,
                input.getSuperAgentId());
        return toVO(inputId, payload, responder, respondedAt);
    }

    private void assertMember(Long conversationId, Long userId) {
        Long count = participantMapper.selectCount(Wrappers.<ImParticipant>lambdaQuery()
                .eq(ImParticipant::getConversationId, conversationId)
                .eq(ImParticipant::getParticipantType, ImParticipantType.USER)
                .eq(ImParticipant::getParticipantId, userId));
        if (count == null || count == 0) {
            throw new BizException(CommonErrorCode.FORBIDDEN);
        }
    }

    /**
     * Visibility follows the group setting: open groups allow any member, strict groups allow the
     * asker, the avatar owner and group administrators.
     */
    private void assertCanAnswer(ImConversation conversation, SessionInputVO input, Long userId) {
        if (!isStrict(conversation)) {
            return;
        }
        if (input.getRequestedBy() != null && input.getRequestedBy().equals(userId)) {
            return;
        }
        SuperAgentDirectorySpi directory = directoryPortProvider.getIfAvailable();
        if (directory != null && input.getSuperAgentId() != null) {
            ChatAgentIdentity identity = directory.findByIds(List.of(input.getSuperAgentId()))
                    .get(input.getSuperAgentId());
            if (identity != null && userId.equals(identity.ownerUserId())) {
                return;
            }
        }
        ImParticipant member = participantMapper.selectOne(Wrappers.<ImParticipant>lambdaQuery()
                .eq(ImParticipant::getConversationId, conversation.getId())
                .eq(ImParticipant::getParticipantType, ImParticipantType.USER)
                .eq(ImParticipant::getParticipantId, userId)
                .last("LIMIT 1"));
        if (member != null && (ImParticipantRole.OWNER.equals(member.getRole())
                || ImParticipantRole.ADMIN.equals(member.getRole()))) {
            return;
        }
        throw new BizException(CommonErrorCode.FORBIDDEN,
                "Only the asker, the avatar owner or a group admin can answer this clarification");
    }

    private boolean isStrict(ImConversation conversation) {
        if (conversation.getGateConfig() == null || conversation.getGateConfig().isBlank()) {
            return false;
        }
        try {
            ImGateConfig config = JsonUtils.parse(conversation.getGateConfig(), ImGateConfig.class);
            return config != null && Boolean.TRUE.equals(config.getClarificationStrict());
        } catch (Exception e) {
            return false;
        }
    }

    private AnswerPayload validatePayload(SessionInputVO input, List<String> options,
                                          AnswerClarificationDTO dto) {
        String requestedMode = dto.getMode() == null ? "" : dto.getMode().trim().toUpperCase();
        if (options.isEmpty()) {
            String text = dto.getText() == null ? "" : dto.getText().trim();
            if (text.isEmpty()) {
                throw new BizException(CommonErrorCode.PARAM_INVALID, "answer text is required");
            }
            if (text.length() > AnswerClarificationDTO.MAX_TEXT_CHARS) {
                throw new BizException(CommonErrorCode.PARAM_INVALID, "answer text is too long");
            }
            if (!requestedMode.isEmpty()
                    && !AnswerClarificationDTO.MODE_FREE_TEXT.equals(requestedMode)) {
                throw new BizException(CommonErrorCode.PARAM_INVALID,
                        "clarification requires free text");
            }
            return new AnswerPayload(AnswerClarificationDTO.MODE_FREE_TEXT, text, List.of());
        }
        boolean multiple = Boolean.TRUE.equals(input.getMultiple());
        String expectedMode = multiple
                ? AnswerClarificationDTO.MODE_MULTI : AnswerClarificationDTO.MODE_SINGLE;
        if (!requestedMode.isEmpty() && !expectedMode.equals(requestedMode)) {
            throw new BizException(CommonErrorCode.PARAM_INVALID, "clarification mode mismatch");
        }
        List<String> selected = dto.getSelectedOptions() == null
                ? List.of() : dto.getSelectedOptions().stream()
                        .filter(Objects::nonNull)
                        .map(String::trim)
                        .filter(value -> !value.isEmpty())
                        .distinct()
                        .toList();
        if (selected.isEmpty()) {
            throw new BizException(CommonErrorCode.PARAM_INVALID, "an option is required");
        }
        if (!multiple && selected.size() != 1) {
            throw new BizException(CommonErrorCode.PARAM_INVALID, "only one option is allowed");
        }
        if (!options.containsAll(selected)) {
            throw new BizException(CommonErrorCode.PARAM_INVALID, "unknown option");
        }
        return new AnswerPayload(expectedMode, String.join(", ", selected), selected);
    }

    private List<String> parseOptions(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            List<Object> raw = JsonUtils.parse(json, new TypeReference<List<Object>>() {
            });
            if (raw == null) {
                return List.of();
            }
            List<String> options = new ArrayList<>();
            for (Object item : raw) {
                String text = optionText(item);
                if (text != null && !text.isBlank()) {
                    options.add(text);
                }
            }
            return options;
        } catch (Exception e) {
            return List.of();
        }
    }

    private String optionText(Object option) {
        if (option instanceof String text) {
            return text.trim();
        }
        if (option instanceof java.util.Map<?, ?> map) {
            for (String key : OPTION_TEXT_KEYS) {
                Object value = map.get(key);
                if (value instanceof String text && !text.isBlank()) {
                    return text.trim();
                }
            }
        }
        return null;
    }

    private void updateCard(Long conversationId, String inputId, AnswerPayload payload,
                            String responder, String respondedAt, Long superAgentId) {
        List<ImMessage> cards = messageMapper.selectList(Wrappers.<ImMessage>lambdaQuery()
                .eq(ImMessage::getConversationId, conversationId)
                .eq(ImMessage::getType, ImMessageType.CARD)
                .orderByDesc(ImMessage::getId));
        for (ImMessage cardMessage : cards) {
            TaskCardPayload card = parseCard(cardMessage.getContent());
            if (card == null || !Objects.equals(inputId, card.getInputId())) {
                continue;
            }
            card.setStatus(STATUS_ANSWERED);
            card.setDecisionsJson(JsonUtils.toJson(new ClarificationDecision(
                    payload.answerText(), payload.mode(), payload.selectedOptions())));
            card.setRespondedBy(responder);
            card.setRespondedAt(respondedAt);
            cardMessage.setContent(JsonUtils.toJson(card));
            messageMapper.updateById(cardMessage);
            publishCardUpdate(conversationId, cardMessage, superAgentId);
            return;
        }
    }

    private void publishCardUpdate(Long conversationId, ImMessage card, Long superAgentId) {
        List<ImParticipant> members = participantMapper.selectList(
                Wrappers.<ImParticipant>lambdaQuery()
                        .eq(ImParticipant::getConversationId, conversationId)
                        .eq(ImParticipant::getParticipantType, ImParticipantType.USER));
        ImStreamEvent event = ImStreamEvent.builder()
                .type(ImStreamEventType.MESSAGE_UPDATED)
                .conversationId(conversationId)
                .messageId(card.getId())
                .seq(card.getSeq())
                .senderType(card.getSenderType())
                .senderId(superAgentId)
                .timestamp(System.currentTimeMillis())
                .createdAt(card.getCreatedAt() == null ? null : card.getCreatedAt().format(DTF))
                .build();
        for (ImParticipant member : members) {
            if (member.getParticipantId() != null) {
                streamService.publish(member.getParticipantId(), event);
            }
        }
    }

    private TaskCardPayload parseCard(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return JsonUtils.parse(json, TaskCardPayload.class);
        } catch (Exception e) {
            return null;
        }
    }

    private ClarificationAnswerVO toVO(String inputId, AnswerPayload payload,
                                       String responder, String respondedAt) {
        ClarificationAnswerVO vo = new ClarificationAnswerVO();
        vo.setInputId(inputId);
        vo.setStatus(STATUS_ANSWERED);
        vo.setMode(payload.mode());
        vo.setAnswer(payload.answerText());
        vo.setSelectedOptions(new ArrayList<>(payload.selectedOptions()));
        vo.setRespondedBy(responder);
        vo.setRespondedAt(respondedAt);
        return vo;
    }

    private static Long currentUserId() {
        Long userId = AuthContext.getUserId();
        if (userId == null) {
            throw new BizException(CommonErrorCode.UNAUTHORIZED);
        }
        return userId;
    }

    /** SPI calls clear request context internally; the card flow must not leak that to callers. */
    private <T> T withCallerContext(java.util.function.Supplier<T> supplier) {
        AgentContextSnapshot caller = AgentContextSnapshot.capture();
        try {
            return supplier.get();
        } finally {
            caller.restore();
        }
    }

    private record AnswerPayload(String mode, String answerText, List<String> selectedOptions) {
    }

    /** Typed decisions_json body stored on the resolved clarification card. */
    private record ClarificationDecision(String answer, String mode, List<String> selectedOptions) {
    }
}
