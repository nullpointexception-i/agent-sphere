package com.buukle.agent.im.service.impl;

import com.buukle.agent.common.context.TenantScopeHolder;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.buukle.agent.common.context.AuthContext;
import com.buukle.agent.common.context.LocaleContext;
import com.buukle.agent.common.context.TenantContext;
import com.buukle.agent.common.context.TenantUtil;
import com.buukle.agent.common.error.CommonErrorCode;
import com.buukle.agent.common.exception.BizException;
import com.buukle.agent.im.domain.ImConversation;
import com.buukle.agent.im.domain.ImConversationSetting;
import com.buukle.agent.im.domain.ImMessage;
import com.buukle.agent.im.domain.ImParticipant;
import com.buukle.agent.im.domain.ImReadCursor;
import com.buukle.agent.im.dtvo.dto.AddParticipantDTO;
import com.buukle.agent.im.dtvo.dto.CreateConversationDTO;
import com.buukle.agent.im.dtvo.dto.EntityConversationDTO;
import com.buukle.agent.im.dtvo.dto.ImGateConfig;
import com.buukle.agent.im.dtvo.dto.MarkReadDTO;
import com.buukle.agent.im.dtvo.dto.UpdateConversationSettingDTO;
import com.buukle.agent.im.dtvo.enums.ConversationAgentSource;
import com.buukle.agent.im.dtvo.enums.ImConversationStatus;
import com.buukle.agent.im.dtvo.enums.ImConversationType;
import com.buukle.agent.im.dtvo.enums.ImStreamEventType;
import com.buukle.agent.im.dtvo.enums.ImMessageStatus;
import com.buukle.agent.im.dtvo.enums.ImParticipantRole;
import com.buukle.agent.im.dtvo.enums.ImParticipantType;
import com.buukle.agent.im.dtvo.vo.ConversationAgentVO;
import com.buukle.agent.im.dtvo.vo.ConversationPageVO;
import com.buukle.agent.im.dtvo.vo.ConversationSettingVO;
import com.buukle.agent.im.dtvo.vo.ConversationSummaryVO;
import com.buukle.agent.im.dtvo.vo.ConversationVO;
import com.buukle.agent.im.dtvo.vo.ImStreamEvent;
import com.buukle.agent.im.dtvo.vo.MessageVO;
import com.buukle.agent.im.dtvo.vo.ParticipantVO;
import com.buukle.agent.im.repository.ImConversationMapper;
import com.buukle.agent.im.repository.ImConversationSettingMapper;
import com.buukle.agent.im.repository.ImMessageMapper;
import com.buukle.agent.im.repository.ImParticipantMapper;
import com.buukle.agent.im.repository.ImReadCursorMapper;
import com.buukle.agent.im.service.ImConversationService;
import com.buukle.agent.im.service.ImStreamService;
import com.buukle.agent.instance.dtvo.vo.UserIdentityVO;
import com.buukle.agent.instance.spi.UserSpi;
import com.buukle.agent.sso.spi.SsoIdentitySpi;
import com.buukle.agent.sso.spi.SsoUserLabelVO;
import com.buukle.agent.superagent.dtvo.enums.SuperAgentStatus;
import com.buukle.agent.superagent.spi.ChatAgentIdentity;
import com.buukle.agent.superagent.spi.EntityAccessGuard;
import com.buukle.agent.superagent.spi.EntityAgentDirectorySpi;
import com.buukle.agent.superagent.spi.EntityConversationPurpose;
import com.buukle.agent.superagent.spi.EntityTypes;
import com.buukle.agent.superagent.spi.GroupChatBootstrapSpi;
import com.buukle.agent.superagent.spi.SuperAgentDirectorySpi;
import com.buukle.agent.util.json.JsonUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class ImConversationServiceImpl implements ImConversationService {

    private static final DateTimeFormatter DTF = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final String DEFAULT_DEVICE = "default";
    private static final int DEFAULT_PAGE_SIZE = 50;
    private static final int MAX_PAGE_SIZE = 100;
    private static final String CURSOR_SEPARATOR = "|";
    private static final Set<String> ENTITY_BIZ_TYPES = Set.of(
            EntityTypes.PROJECT, EntityTypes.REQUIREMENT,
            EntityTypes.APPLICATION, EntityTypes.APPLICATION_RUN);
    private static final Set<String> ENTITY_BIZ_PURPOSES = Set.of(
            EntityConversationPurpose.DISCUSSION,
            EntityConversationPurpose.CHAT,
            EntityConversationPurpose.RUN);

    private final ImConversationMapper conversationMapper;
    private final ImParticipantMapper participantMapper;
    private final ImMessageMapper messageMapper;
    private final ImReadCursorMapper readCursorMapper;
    private final ImConversationSettingMapper settingMapper;
    private final UserSpi userSpi;
    private final SsoIdentitySpi ssoIdentitySpi;
    private final ImStreamService streamService;
    private final ObjectProvider<SuperAgentDirectorySpi> directoryPortProvider;
    private final ObjectProvider<GroupChatBootstrapSpi> groupChatBootstrapProvider;
    private final ObjectProvider<EntityAccessGuard> entityAccessGuardProvider;
    private final ObjectProvider<EntityAgentDirectorySpi> entityAgentDirectoryProvider;

    @Override
    @Transactional
    public ConversationVO create(CreateConversationDTO dto) {
        Long userId = currentUserId();
        String type = dto.getType() == null ? ImConversationType.DM : dto.getType().toUpperCase();
        if (!ImConversationType.DM.equals(type) && !ImConversationType.GROUP.equals(type)) {
            throw new BizException(CommonErrorCode.PARAM_INVALID, "type must be DM or GROUP");
        }
        if (ImConversationType.DM.equals(type)) {
            Long otherUserId = firstOtherUser(dto.getParticipantUserIds(), userId);
            ImConversation existing = findExistingDm(userId, otherUserId, null);
            if (existing != null) {
                revealHiddenConversation(existing.getId(), userId);
                return toVO(existing, userId);
            }
        }
        ImConversation conversation = new ImConversation();
        conversation.setType(type);
        conversation.setTitle(dto.getTitle());
        conversation.setPurpose(dto.getPurpose());
        conversation.setOwnerUserId(userId);
        conversation.setStatus(ImConversationStatus.ACTIVE);
        conversation.setLastSeq(0L);
        conversation.setGateConfig(dto.getGateConfig());
        conversationMapper.insert(conversation);

        addParticipantInternal(conversation.getId(), userId, ImParticipantType.USER,
                ImParticipantRole.OWNER, displayName());
        if (dto.getParticipantUserIds() != null) {
            for (Long participantUserId : new LinkedHashSet<>(dto.getParticipantUserIds())) {
                if (participantUserId == null || participantUserId.equals(userId)) {
                    continue;
                }
                addParticipantInternal(conversation.getId(), participantUserId,
                        ImParticipantType.USER, ImParticipantRole.MEMBER, null);
            }
        }
        // Group avatars are not participants: they are resolved dynamically from
        // the members' public avatar catalogue on every read and dispatch.
        bootstrapConversation(conversation, userId, type);
        publishToMembersAfterCommit(conversation.getId(), ImStreamEvent.builder()
                .type(ImStreamEventType.CONVERSATION_CREATED)
                .conversationId(conversation.getId())
                .title(conversation.getTitle())
                .timestamp(System.currentTimeMillis())
                .build());
        return toVO(conversation, userId);
    }

    @Override
    public List<ConversationVO> list() {
        Long userId = currentUserId();
        List<Long> conversationIds = systemRead(() -> participantMapper.selectList(
                        Wrappers.<ImParticipant>lambdaQuery()
                                .eq(ImParticipant::getParticipantType, ImParticipantType.USER)
                                .eq(ImParticipant::getParticipantId, userId)))
                .stream().map(ImParticipant::getConversationId).toList();
        if (conversationIds.isEmpty()) {
            return List.of();
        }
        List<ImConversation> conversations = systemRead(() -> conversationMapper.selectList(
                Wrappers.<ImConversation>lambdaQuery()
                        .in(ImConversation::getId, conversationIds)
                        .eq(ImConversation::getStatus, ImConversationStatus.ACTIVE)
                        .orderByDesc(ImConversation::getLastMessageAt)
                        .orderByDesc(ImConversation::getId)));
        Map<Long, ImConversationSetting> settings = settingsOf(userId, conversationIds);
        conversations.removeIf(conversation -> isHidden(settings.get(conversation.getId())));
        conversations.sort(java.util.Comparator.comparing(
                conversation -> !isPinned(settings.get(conversation.getId()))));
        List<ConversationVO> result = new ArrayList<>(conversations.size());
        for (ImConversation conversation : conversations) {
            result.add(toVO(conversation, userId, settings.get(conversation.getId())));
        }
        return result;
    }

    @Override
    public ConversationPageVO page(int limit, String cursor, String type) {
        Long userId = currentUserId();
        int size = limit <= 0 ? DEFAULT_PAGE_SIZE : Math.min(limit, MAX_PAGE_SIZE);
        String conversationType = ImConversationType.DM.equalsIgnoreCase(type)
                || ImConversationType.GROUP.equalsIgnoreCase(type)
                ? type.toUpperCase() : null;
        Map<Long, ImConversationSetting> settings = settingsOf(userId);
        List<Long> hiddenIds = settings.values().stream()
                .filter(ImConversationServiceImpl::isHidden)
                .map(ImConversationSetting::getConversationId)
                .toList();
        List<Long> pinnedIds = settings.values().stream()
                .filter(ImConversationServiceImpl::isPinned)
                .filter(setting -> !isHidden(setting))
                .map(ImConversationSetting::getConversationId)
                .toList();
        List<Long> excludedIds = new ArrayList<>(pinnedIds);
        excludedIds.addAll(hiddenIds);
        List<Long> excluded = excludedIds.stream().distinct().toList();
        List<ImConversation> pinned = pinnedIds.isEmpty() ? new ArrayList<>()
                : new ArrayList<>(systemRead(() -> conversationMapper.selectBatchIds(pinnedIds)));
        if (conversationType != null) {
            pinned.removeIf(item -> !conversationType.equals(item.getType()));
        }
        pinned.sort(Comparator.comparing(ImConversationServiceImpl::sortTime).reversed());

        PageCursor parsed = parseCursor(cursor);
        List<ImConversation> rows = systemRead(() -> conversationMapper.pageForUser(
                userId, excluded, conversationType,
                parsed == null ? null : parsed.time(),
                parsed == null ? null : parsed.id(), size + 1));
        boolean hasMore = rows.size() > size;
        if (hasMore) {
            rows = new ArrayList<>(rows.subList(0, size));
        }

        List<ImConversation> all = new ArrayList<>(pinned.size() + rows.size());
        all.addAll(pinned);
        all.addAll(rows);
        Map<Long, ConversationSummaryVO> summaries = buildSummaries(all, userId, settings);

        ConversationPageVO page = new ConversationPageVO();
        page.setPinned(pinned.stream().map(item -> summaries.get(item.getId())).toList());
        page.setRows(rows.stream().map(item -> summaries.get(item.getId())).toList());
        page.setHasMore(hasMore);
        if (hasMore && !rows.isEmpty()) {
            ImConversation last = rows.get(rows.size() - 1);
            page.setNextCursor(sortTime(last) + CURSOR_SEPARATOR + last.getId());
        }
        return page;
    }

    private record PageCursor(LocalDateTime time, Long id) {
    }

    private static PageCursor parseCursor(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return null;
        }
        int separator = cursor.lastIndexOf(CURSOR_SEPARATOR);
        if (separator <= 0 || separator == cursor.length() - 1) {
            return null;
        }
        try {
            return new PageCursor(
                    LocalDateTime.parse(cursor.substring(0, separator)),
                    Long.parseLong(cursor.substring(separator + 1)));
        } catch (Exception ignored) {
            return null;
        }
    }

    private static LocalDateTime sortTime(ImConversation conversation) {
        return conversation.getLastMessageAt() != null
                ? conversation.getLastMessageAt() : conversation.getCreatedAt();
    }

    private Map<Long, ConversationSummaryVO> buildSummaries(
            List<ImConversation> conversations, Long userId,
            Map<Long, ImConversationSetting> settings) {
        if (conversations.isEmpty()) {
            return Map.of();
        }
        List<Long> conversationIds = conversations.stream().map(ImConversation::getId).toList();
        List<ImParticipant> participants = systemRead(() -> participantMapper.selectList(
                Wrappers.<ImParticipant>lambdaQuery()
                        .in(ImParticipant::getConversationId, conversationIds)));
        Map<Long, List<ImParticipant>> membersByConversation = participants.stream()
                .collect(Collectors.groupingBy(ImParticipant::getConversationId));

        Map<Long, ImMessage> lastMessages = Map.of();
        List<Long> lastMessageIds = conversations.stream()
                .map(ImConversation::getLastMessageId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        if (!lastMessageIds.isEmpty()) {
            lastMessages = systemRead(() -> messageMapper.selectBatchIds(lastMessageIds)).stream()
                    .collect(Collectors.toMap(ImMessage::getId, message -> message, (left, right) -> left));
        }

        Map<Long, Long> readSeqs = systemRead(() -> readCursorMapper.selectList(
                        Wrappers.<ImReadCursor>lambdaQuery()
                                .eq(ImReadCursor::getUserId, userId)
                                .eq(ImReadCursor::getDeviceId, DEFAULT_DEVICE)
                                .in(ImReadCursor::getConversationId, conversationIds)))
                .stream()
                .collect(Collectors.toMap(ImReadCursor::getConversationId,
                        cursor -> cursor.getLastReadSeq() == null ? 0L : cursor.getLastReadSeq(),
                        (left, right) -> left));

        // Reuse the same identity resolution as conversation detail: it attaches the SSO
        // provider@username label, so list rows and the header match the message sender.
        Map<Long, UserIdentityVO> identities = identitiesOf(participants);

        Map<Long, ConversationSummaryVO> result = new java.util.HashMap<>();
        for (ImConversation conversation : conversations) {
            ConversationSummaryVO summary = new ConversationSummaryVO();
            summary.setId(conversation.getId());
            summary.setType(conversation.getType());
            summary.setTitle(conversation.getTitle());
            summary.setPurpose(conversation.getPurpose());
            summary.setOwnerUserId(conversation.getOwnerUserId());
            summary.setLastSeq(conversation.getLastSeq());
            summary.setStatus(conversation.getStatus());
            summary.setLastMessageAt(conversation.getLastMessageAt() == null ? null
                    : conversation.getLastMessageAt().format(DTF));
            ImConversationSetting setting = settings.get(conversation.getId());
            summary.setPinned(isPinned(setting));
            summary.setMuted(setting != null && Boolean.TRUE.equals(setting.getMuted()));
            summary.setRemark(setting == null ? null : setting.getRemark());
            long lastSeq = conversation.getLastSeq() == null ? 0L : conversation.getLastSeq();
            long readSeq = readSeqs.getOrDefault(conversation.getId(), 0L);
            summary.setUnread(Math.max(0L, lastSeq - readSeq));
            ImMessage lastMessage = conversation.getLastMessageId() == null ? null
                    : lastMessages.get(conversation.getLastMessageId());
            if (lastMessage != null) {
                summary.setLastMessage(toMessageVO(lastMessage));
            }

            List<ImParticipant> members = membersByConversation.getOrDefault(conversation.getId(), List.of());
            summary.setParticipantCount(members.size());
            summary.setMyRole(roleOf(members, userId));
            summary.setAllowMemberInvite(allowMemberInvite(conversation));
            ConversationDisplay display = resolveDisplay(conversation, userId, members, identities);
            summary.setDisplayName(display.displayName());
            summary.setAvatar(display.avatar());
            summary.setSelfConversation(display.selfConversation());
            result.put(conversation.getId(), summary);
        }
        return result;
    }

    @Override
    public ConversationVO get(Long conversationId) {
        Long userId = currentUserId();
        ImConversation conversation = requireConversation(conversationId);
        assertMember(conversationId, userId);
        return toVO(conversation, userId);
    }

    @Override
    public ConversationVO updateMeta(Long conversationId, String title, String purpose) {
        requireConversation(conversationId);
        assertAdmin(conversationId, currentUserId());
        ImConversation conversation = requireConversation(conversationId);
        if (title != null) {
            conversation.setTitle(title);
        }
        if (purpose != null) {
            conversation.setPurpose(purpose);
        }
        conversationMapper.updateById(conversation);
        publishToMembersAfterCommit(conversationId, ImStreamEvent.builder()
                .type(ImStreamEventType.CONVERSATION_UPDATED)
                .conversationId(conversationId)
                .title(conversation.getTitle())
                .timestamp(System.currentTimeMillis())
                .build());
        return toVO(conversation, currentUserId());
    }

    @Override
    public ConversationSettingVO getSetting(Long conversationId) {
        Long userId = currentUserId();
        assertMember(conversationId, userId);
        return toSettingVO(conversationId, findSetting(conversationId, userId));
    }

    @Override
    @Transactional
    public ConversationSettingVO updateSetting(Long conversationId, UpdateConversationSettingDTO dto) {
        Long userId = currentUserId();
        assertMember(conversationId, userId);
        ImConversationSetting setting = findSetting(conversationId, userId);
        if (setting == null) {
            setting = new ImConversationSetting();
            setting.setConversationId(conversationId);
            setting.setUserId(userId);
            setting.setPinned(false);
            setting.setMuted(false);
            setting.setStatus(ImConversationStatus.ACTIVE);
        }
        if (dto != null) {
            if (dto.getPinned() != null) {
                setting.setPinned(dto.getPinned());
            }
            if (dto.getMuted() != null) {
                setting.setMuted(dto.getMuted());
            }
            if (dto.getRemark() != null) {
                setting.setRemark(dto.getRemark().trim());
            }
        }
        if (setting.getId() == null) {
            settingMapper.insert(setting);
        } else {
            settingMapper.updateById(setting);
        }
        ConversationSettingVO vo = toSettingVO(conversationId, setting);
        publishToUserAfterCommit(userId, ImStreamEvent.builder()
                .type(ImStreamEventType.CONVERSATION_SETTING_CHANGED)
                .conversationId(conversationId)
                .pinned(vo.getPinned())
                .muted(vo.getMuted())
                .remark(vo.getRemark())
                .timestamp(System.currentTimeMillis())
                .build());
        return vo;
    }

    @Override
    @Transactional
    public void hide(Long conversationId) {
        Long userId = currentUserId();
        assertMember(conversationId, userId);
        ImConversationSetting setting = findSetting(conversationId, userId);
        if (setting == null) {
            setting = new ImConversationSetting();
            setting.setConversationId(conversationId);
            setting.setUserId(userId);
            setting.setPinned(false);
            setting.setMuted(false);
            setting.setStatus(ImConversationStatus.ACTIVE);
        }
        setting.setHidden(true);
        if (setting.getId() == null) {
            settingMapper.insert(setting);
        } else {
            settingMapper.updateById(setting);
        }
        publishToUserAfterCommit(userId, ImStreamEvent.builder()
                .type(ImStreamEventType.CONVERSATION_HIDDEN)
                .conversationId(conversationId)
                .timestamp(System.currentTimeMillis())
                .build());
    }

    @Override
    @Transactional
    public ConversationVO pinMessage(Long conversationId, Long messageId) {
        Long userId = currentUserId();
        requireConversation(conversationId);
        assertMember(conversationId, userId);
        ImMessage message = systemRead(() -> messageMapper.selectById(messageId));
        if (message == null || !conversationId.equals(message.getConversationId())) {
            throw new BizException(CommonErrorCode.PARAM_INVALID, "message not found");
        }
        conversationMapper.updatePinnedMessage(conversationId, messageId);
        publishToMembersAfterCommit(conversationId, ImStreamEvent.builder()
                .type(ImStreamEventType.MESSAGE_PINNED)
                .conversationId(conversationId)
                .messageId(messageId)
                .timestamp(System.currentTimeMillis())
                .build());
        return toVO(requireConversation(conversationId), userId);
    }

    @Override
    @Transactional
    public ConversationVO unpinMessage(Long conversationId) {
        Long userId = currentUserId();
        requireConversation(conversationId);
        assertMember(conversationId, userId);
        conversationMapper.updatePinnedMessage(conversationId, null);
        publishToMembersAfterCommit(conversationId, ImStreamEvent.builder()
                .type(ImStreamEventType.MESSAGE_UNPINNED)
                .conversationId(conversationId)
                .timestamp(System.currentTimeMillis())
                .build());
        return toVO(requireConversation(conversationId), userId);
    }

    @Override
    public List<ParticipantVO> participants(Long conversationId) {        assertMember(conversationId, currentUserId());
        List<ImParticipant> participants = systemRead(() -> participantMapper.selectList(
                Wrappers.<ImParticipant>lambdaQuery()
                        .eq(ImParticipant::getConversationId, conversationId)
                        .orderByAsc(ImParticipant::getId)));
        Map<Long, UserIdentityVO> identities = identitiesOf(participants);
        Map<Long, Long> agentOwners = agentOwnersOf(participants);
        return participants.stream()
                .map(participant -> toParticipantVO(participant, identities, agentOwners))
                .toList();
    }

    @Override
    public List<ConversationAgentVO> agents(Long conversationId) {
        assertMember(conversationId, currentUserId());
        ImConversation conversation = requireConversation(conversationId);
        Map<Long, ConversationAgentVO> merged = new LinkedHashMap<>();
        if (ImConversationType.GROUP.equals(conversation.getType())) {
            List<Long> memberIds = systemRead(() -> participantMapper.selectList(
                            Wrappers.<ImParticipant>lambdaQuery()
                                    .eq(ImParticipant::getConversationId, conversationId)
                                    .eq(ImParticipant::getParticipantType, ImParticipantType.USER)))
                    .stream()
                    .map(ImParticipant::getParticipantId)
                    .filter(Objects::nonNull)
                    .distinct()
                    .toList();
            if (!memberIds.isEmpty()) {
                for (ChatAgentIdentity identity : requireDirectory().listPublicByOwners(memberIds)) {
                    putAgent(merged, identity.agentId(), identity.name(), identity.ownerUserId(),
                            identity.status(), ConversationAgentSource.MEMBER_PUBLIC);
                }
            }
        } else {
            List<ImParticipant> agentRows = systemRead(() -> participantMapper.selectList(
                    Wrappers.<ImParticipant>lambdaQuery()
                            .eq(ImParticipant::getConversationId, conversationId)
                            .eq(ImParticipant::getParticipantType, ImParticipantType.AGENT)));
            Map<Long, Long> owners = agentOwnersOf(agentRows);
            for (ImParticipant row : agentRows) {
                putAgent(merged, row.getParticipantId(), row.getDisplayName(),
                        owners.get(row.getParticipantId()), null,
                        ConversationAgentSource.PARTICIPANT);
            }
        }
        addEntityBoundAgents(merged, conversation);
        return new ArrayList<>(merged.values());
    }

    /**
     * Entity-bound avatars are merged into the same list so the mention picker
     * shows one catalogue instead of asking the UI to call two endpoints.
     */
    private void addEntityBoundAgents(Map<Long, ConversationAgentVO> merged,
                                      ImConversation conversation) {
        if (conversation == null || conversation.getBizType() == null
                || conversation.getBizId() == null) {
            return;
        }
        EntityAgentDirectorySpi entityDirectory = entityAgentDirectoryProvider.getIfAvailable();
        if (entityDirectory == null) {
            return;
        }
        List<Long> boundIds = entityDirectory.listBoundAgentIds(
                conversation.getBizType(), conversation.getBizId(), currentUserId());
        List<Long> missing = boundIds.stream()
                .filter(id -> id != null && !merged.containsKey(id))
                .distinct()
                .toList();
        if (missing.isEmpty()) {
            return;
        }
        Map<Long, ChatAgentIdentity> identities = requireDirectory().findByIds(missing);
        for (Long agentId : missing) {
            ChatAgentIdentity identity = identities.get(agentId);
            putAgent(merged, agentId,
                    identity == null ? null : identity.name(),
                    identity == null ? null : identity.ownerUserId(),
                    identity == null ? null : identity.status(),
                    ConversationAgentSource.ENTITY_BINDING);
        }
    }

    private void putAgent(Map<Long, ConversationAgentVO> merged, Long agentId,
                          String displayName, Long ownerUserId, String status, String source) {
        if (agentId == null || merged.containsKey(agentId)) {
            return;
        }
        ConversationAgentVO vo = new ConversationAgentVO();
        vo.setParticipantId(agentId);
        vo.setDisplayName(displayName);
        vo.setOwnerUserId(ownerUserId);
        vo.setStatus(status);
        vo.setSource(source);
        merged.put(agentId, vo);
    }

    @Override
    @Transactional
    public ParticipantVO addParticipant(Long conversationId, AddParticipantDTO dto) {
        Long userId = currentUserId();
        ImConversation conversation = requireConversation(conversationId);
        String type = dto.getParticipantType().toUpperCase();
        if (!ImParticipantType.USER.equals(type) && !ImParticipantType.AGENT.equals(type)) {
            throw new BizException(CommonErrorCode.PARAM_INVALID, "participantType must be USER or AGENT");
        }
        if (ImParticipantType.AGENT.equals(type)
                && ImConversationType.GROUP.equals(conversation.getType())) {
            throw new BizException(CommonErrorCode.PARAM_INVALID,
                    "group avatars are resolved from members' public avatars; they are not members");
        }
        assertCanAddParticipant(conversation, userId, type);
        String displayName = dto.getDisplayName();
        if (ImParticipantType.AGENT.equals(type)) {
            SuperAgentDirectorySpi directory = requireDirectory();
            ChatAgentIdentity identity = directory.resolveForGroupChat(
                    dto.getParticipantId(), userId);
            if (displayName == null || displayName.isBlank()) {
                displayName = identity.name();
            }
        }
        String role = dto.getRole() == null ? ImParticipantRole.MEMBER : dto.getRole().toUpperCase();
        ImParticipant participant = addParticipantInternal(conversationId, dto.getParticipantId(),
                type, role, displayName);
        publishToMembersAfterCommit(conversationId, ImStreamEvent.builder()
                .type(ImStreamEventType.PARTICIPANT_CHANGED)
                .conversationId(conversationId)
                .timestamp(System.currentTimeMillis())
                .build());
        List<ImParticipant> added = List.of(participant);
        return toParticipantVO(participant, identitiesOf(added), agentOwnersOf(added));
    }

    @Override
    @Transactional
    public void removeParticipant(Long conversationId, Long participantId) {
        requireConversation(conversationId);
        Long callerId = currentUserId();
        assertAdmin(conversationId, callerId);
        ImParticipant participant = systemRead(() -> participantMapper.selectOne(Wrappers.<ImParticipant>lambdaQuery()
                .eq(ImParticipant::getConversationId, conversationId)
                .eq(ImParticipant::getParticipantId, participantId)
                .last("LIMIT 1")));
        if (participant == null) {
            return;
        }
        if (ImParticipantRole.OWNER.equals(participant.getRole())) {
            throw new BizException(CommonErrorCode.PARAM_INVALID, "owner cannot be removed");
        }
        if (ImParticipantRole.ADMIN.equals(participant.getRole())) {
            // Admins may promote/demote each other, but only the owner may remove an admin.
            assertOwner(conversationId, callerId);
        }
        participantMapper.deleteById(participant.getId());
        publishToMembersAfterCommit(conversationId, ImStreamEvent.builder()
                .type(ImStreamEventType.PARTICIPANT_CHANGED)
                .conversationId(conversationId)
                .timestamp(System.currentTimeMillis())
                .build());
    }

    @Override
    @Transactional
    public ParticipantVO updateParticipantRole(Long conversationId, Long participantId, String role) {
        requireConversation(conversationId);
        assertAdmin(conversationId, currentUserId());
        String nextRole = role == null ? "" : role.toUpperCase();
        if (!ImParticipantRole.ADMIN.equals(nextRole) && !ImParticipantRole.MEMBER.equals(nextRole)) {
            throw new BizException(CommonErrorCode.PARAM_INVALID, "role must be ADMIN or MEMBER");
        }
        ImParticipant participant = systemRead(() -> participantMapper.selectOne(Wrappers.<ImParticipant>lambdaQuery()
                .eq(ImParticipant::getConversationId, conversationId)
                .eq(ImParticipant::getParticipantType, ImParticipantType.USER)
                .eq(ImParticipant::getParticipantId, participantId)
                .last("LIMIT 1")));
        if (participant == null) {
            throw new BizException(CommonErrorCode.PARAM_INVALID, "participant not found");
        }
        if (ImParticipantRole.OWNER.equals(participant.getRole())) {
            throw new BizException(CommonErrorCode.PARAM_INVALID, "owner role cannot be changed; transfer ownership instead");
        }
        participant.setRole(nextRole);
        participantMapper.updateById(participant);
        publishToMembersAfterCommit(conversationId, ImStreamEvent.builder()
                .type(ImStreamEventType.PARTICIPANT_CHANGED)
                .conversationId(conversationId)
                .timestamp(System.currentTimeMillis())
                .build());
        List<ImParticipant> updated = List.of(participant);
        return toParticipantVO(participant, identitiesOf(updated), agentOwnersOf(updated));
    }

    @Override
    @Transactional
    public ConversationVO transferOwner(Long conversationId, Long newOwnerUserId) {
        ImConversation conversation = requireConversation(conversationId);
        Long callerId = currentUserId();
        assertOwner(conversationId, callerId);
        if (newOwnerUserId == null || newOwnerUserId.equals(callerId)) {
            return toVO(conversation, callerId);
        }
        ImParticipant target = systemRead(() -> participantMapper.selectOne(
                Wrappers.<ImParticipant>lambdaQuery()
                        .eq(ImParticipant::getConversationId, conversationId)
                        .eq(ImParticipant::getParticipantType, ImParticipantType.USER)
                        .eq(ImParticipant::getParticipantId, newOwnerUserId)
                        .last("LIMIT 1")));
        if (target == null) {
            throw new BizException(CommonErrorCode.PARAM_INVALID, "target user is not a group member");
        }
        ImParticipant oldOwner = systemRead(() -> participantMapper.selectOne(
                Wrappers.<ImParticipant>lambdaQuery()
                        .eq(ImParticipant::getConversationId, conversationId)
                        .eq(ImParticipant::getParticipantType, ImParticipantType.USER)
                        .eq(ImParticipant::getParticipantId, callerId)
                        .last("LIMIT 1")));
        if (oldOwner != null) {
            oldOwner.setRole(ImParticipantRole.ADMIN);
            participantMapper.updateById(oldOwner);
        }
        target.setRole(ImParticipantRole.OWNER);
        participantMapper.updateById(target);
        conversation.setOwnerUserId(newOwnerUserId);
        conversationMapper.updateById(conversation);
        publishToMembersAfterCommit(conversationId, ImStreamEvent.builder()
                .type(ImStreamEventType.PARTICIPANT_CHANGED)
                .conversationId(conversationId)
                .timestamp(System.currentTimeMillis())
                .build());
        return toVO(conversation, callerId);
    }

    @Override
    @Transactional
    public void updateParticipantMute(Long conversationId, Long participantId, boolean muted) {
        requireConversation(conversationId);
        assertAdmin(conversationId, currentUserId());
        ImParticipant participant = systemRead(() -> participantMapper.selectOne(
                Wrappers.<ImParticipant>lambdaQuery()
                        .eq(ImParticipant::getConversationId, conversationId)
                        .eq(ImParticipant::getParticipantId, participantId)
                        .last("LIMIT 1")));
        if (participant == null) {
            throw new BizException(CommonErrorCode.PARAM_INVALID, "participant not found");
        }
        participant.setMuted(muted);
        participantMapper.updateById(participant);
    }

    @Override
    @Transactional
    public void markRead(Long conversationId, MarkReadDTO dto) {
        Long userId = currentUserId();
        ImConversation conversation = requireConversation(conversationId);
        assertMember(conversationId, userId);
        long seq = dto.getLastReadSeq() == null ? 0L : Math.max(0L, dto.getLastReadSeq());
        seq = Math.min(seq, conversation.getLastSeq() == null ? 0L : conversation.getLastSeq());
        String deviceId = dto.getDeviceId() == null || dto.getDeviceId().isBlank()
                ? DEFAULT_DEVICE : dto.getDeviceId();
        ImReadCursor cursor = readCursorMapper.selectOne(Wrappers.<ImReadCursor>lambdaQuery()
                .eq(ImReadCursor::getConversationId, conversationId)
                .eq(ImReadCursor::getUserId, userId)
                .eq(ImReadCursor::getDeviceId, deviceId)
                .last("LIMIT 1"));
        if (cursor == null) {
            cursor = new ImReadCursor();
            cursor.setConversationId(conversationId);
            cursor.setUserId(userId);
            cursor.setDeviceId(deviceId);
            cursor.setLastReadSeq(seq);
            readCursorMapper.insert(cursor);
        } else if (cursor.getLastReadSeq() == null || cursor.getLastReadSeq() < seq) {
            cursor.setLastReadSeq(seq);
            readCursorMapper.updateById(cursor);
        }
        publishToUserAfterCommit(userId, ImStreamEvent.builder()
                .type(ImStreamEventType.UNREAD_CHANGED)
                .conversationId(conversationId)
                .unread(0L)
                .timestamp(System.currentTimeMillis())
                .build());
    }

    @Override
    public ImGateConfig getGateConfig(Long conversationId) {
        ImConversation conversation = requireConversation(conversationId);
        if (conversation.getGateConfig() == null || conversation.getGateConfig().isBlank()) {
            ImGateConfig config = new ImGateConfig();
            config.setTrigger(ImGateConfig.TRIGGER_MENTION);
            config.setAllowMemberInvite(true);
            config.setClarificationStrict(false);
            return config;
        }
        try {
            ImGateConfig config = JsonUtils.parse(conversation.getGateConfig(), ImGateConfig.class);
            if (config == null) {
                config = new ImGateConfig();
            }
            if (config.getTrigger() == null) {
                config.setTrigger(ImGateConfig.TRIGGER_MENTION);
            }
            if (config.getAllowMemberInvite() == null) {
                config.setAllowMemberInvite(true);
            }
            if (config.getClarificationStrict() == null) {
                config.setClarificationStrict(false);
            }
            return config;
        } catch (Exception e) {
            ImGateConfig config = new ImGateConfig();
            config.setTrigger(ImGateConfig.TRIGGER_MENTION);
            config.setAllowMemberInvite(true);
            config.setClarificationStrict(false);
            return config;
        }
    }

    @Override
    public ImGateConfig updateGateConfig(Long conversationId, ImGateConfig config) {
        requireConversation(conversationId);
        assertAdmin(conversationId, currentUserId());
        String trigger = config == null || config.getTrigger() == null
                ? ImGateConfig.TRIGGER_MENTION : config.getTrigger().toUpperCase();
        if (!ImGateConfig.TRIGGER_MENTION.equals(trigger)
                && !ImGateConfig.TRIGGER_RELEVANCE.equals(trigger)
                && !ImGateConfig.TRIGGER_ALL.equals(trigger)) {
            throw new BizException(CommonErrorCode.PARAM_INVALID, "invalid trigger");
        }
        ImGateConfig normalized = getGateConfig(conversationId);
        normalized.setTrigger(trigger);
        if (config != null && config.getAllowMemberInvite() != null) {
            normalized.setAllowMemberInvite(config.getAllowMemberInvite());
        } else if (normalized.getAllowMemberInvite() == null) {
            normalized.setAllowMemberInvite(true);
        }
        if (config != null && config.getClarificationStrict() != null) {
            normalized.setClarificationStrict(config.getClarificationStrict());
        } else if (normalized.getClarificationStrict() == null) {
            normalized.setClarificationStrict(false);
        }
        ImConversation conversation = requireConversation(conversationId);
        conversation.setGateConfig(JsonUtils.toJson(normalized));
        conversationMapper.updateById(conversation);
        return normalized;
    }

    @Override
    public ImGateConfig updateInvitePolicy(Long conversationId, boolean allowMemberInvite) {
        requireConversation(conversationId);
        assertAdmin(conversationId, currentUserId());
        ImGateConfig config = getGateConfig(conversationId);
        config.setAllowMemberInvite(allowMemberInvite);
        ImConversation conversation = requireConversation(conversationId);
        conversation.setGateConfig(JsonUtils.toJson(config));
        conversationMapper.updateById(conversation);
        return config;
    }

    @Override
    public boolean isMember(Long conversationId, Long userId) {
        return systemRead(() -> participantMapper.selectCount(Wrappers.<ImParticipant>lambdaQuery()
                .eq(ImParticipant::getConversationId, conversationId)
                .eq(ImParticipant::getParticipantType, ImParticipantType.USER)
                .eq(ImParticipant::getParticipantId, userId))) > 0;
    }

    private static Long firstOtherUser(List<Long> participantUserIds, Long userId) {
        if (participantUserIds == null) {
            return null;
        }
        for (Long participantUserId : new LinkedHashSet<>(participantUserIds)) {
            if (participantUserId != null && !participantUserId.equals(userId)) {
                return participantUserId;
            }
        }
        return null;
    }

    @Override
    @Transactional
    public ConversationVO ensureEntityConversation(EntityConversationDTO dto) {
        String bizType = normalizeEnum(dto.getBizType());
        String bizPurpose = normalizeEnum(dto.getBizPurpose());
        if (!ENTITY_BIZ_TYPES.contains(bizType) || !ENTITY_BIZ_PURPOSES.contains(bizPurpose)) {
            throw new BizException(CommonErrorCode.PARAM_INVALID, "unsupported entity conversation");
        }
        assertEntityAccess(bizType, dto.getBizId(), currentUserId());
        ConversationVO existing = findEntityConversation(dto);
        if (existing != null) {
            ensureEntityParticipant(existing.getId(), currentUserId());
            return existing;
        }
        Long userId = currentUserId();
        Long tenantId = TenantContext.get() == null ? 0L : TenantContext.get();
        ImConversation conversation = new ImConversation();
        conversation.setType(ImConversationType.GROUP);
        conversation.setPurpose(bizPurpose);
        conversation.setBizType(bizType);
        conversation.setBizId(dto.getBizId());
        conversation.setBizPurpose(bizPurpose);
        conversation.setBizKey(bizType.toLowerCase() + ":" + dto.getBizId() + ":"
                + bizPurpose.toLowerCase());
        conversation.setLocale(LocaleContext.get());
        conversation.setOwnerUserId(EntityTypes.APPLICATION.equals(bizType) ? userId : null);
        conversation.setLastSeq(0L);
        conversation.setStatus(ImConversationStatus.ACTIVE);
        conversation.setTenantId(tenantId);
        try {
            conversationMapper.insert(conversation);
        } catch (DuplicateKeyException duplicate) {
            ConversationVO concurrent = findEntityConversation(dto);
            if (concurrent != null) {
                ensureEntityParticipant(concurrent.getId(), userId);
                return concurrent;
            }
            throw duplicate;
        }
        ensureEntityParticipant(conversation.getId(), userId);
        return toVO(conversation, userId);
    }

    /**
     * Entity conversations are shared by every user who can see the entity, so
     * membership is added lazily and idempotently instead of at creation time
     * only.
     */
    private void ensureEntityParticipant(Long conversationId, Long userId) {
        long exists = systemRead(() -> participantMapper.selectCount(
                Wrappers.<ImParticipant>lambdaQuery()
                        .eq(ImParticipant::getConversationId, conversationId)
                        .eq(ImParticipant::getParticipantType, ImParticipantType.USER)
                        .eq(ImParticipant::getParticipantId, userId)));
        if (exists == 0) {
            addParticipantInternal(conversationId, userId, ImParticipantType.USER,
                    ImParticipantRole.OWNER, displayName());
        }
    }

    @Override
    public ConversationVO findEntityConversation(EntityConversationDTO dto) {
        String bizType = normalizeEnum(dto.getBizType());
        String bizPurpose = normalizeEnum(dto.getBizPurpose());
        Long tenantId = TenantContext.get() == null ? 0L : TenantContext.get();
        assertEntityAccess(bizType, dto.getBizId(), currentUserId());
        com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<ImConversation> wrapper =
                Wrappers.<ImConversation>lambdaQuery()
                        .eq(ImConversation::getTenantId, tenantId)
                        .eq(ImConversation::getBizType, bizType)
                        .eq(ImConversation::getBizId, dto.getBizId())
                        .eq(ImConversation::getBizPurpose, bizPurpose)
                        .last("limit 1");
        if (EntityTypes.APPLICATION.equals(bizType)) {
            wrapper.eq(ImConversation::getOwnerUserId, currentUserId());
        } else {
            wrapper.isNull(ImConversation::getOwnerUserId);
        }
        ImConversation conversation = conversationMapper.selectOne(wrapper);
        return conversation == null ? null : toVO(conversation, currentUserId());
    }

    private String normalizeEnum(String value) {
        return value == null ? "" : value.trim().toUpperCase();
    }

    private void assertEntityAccess(String entityType, Long entityId, Long userId) {
        entityAccessGuardProvider.orderedStream()
                .filter(guard -> guard.supports(entityType))
                .findFirst()
                .ifPresent(guard -> guard.assertCanView(entityType, entityId, userId));
    }

    @Override
    @Transactional
    public ConversationVO createAgentDm(Long agentId) {
        Long userId = currentUserId();
        SuperAgentDirectorySpi directory = directoryPortProvider.getIfAvailable();
        if (directory == null) {
            throw new BizException(CommonErrorCode.PARAM_INVALID,
                    "super-agent directory is unavailable");
        }
        ChatAgentIdentity agent = directory.resolveForChat(agentId, userId);
        if (!SuperAgentStatus.ACTIVE.equals(agent.status())) {
            throw new BizException(CommonErrorCode.PARAM_INVALID,
                    "digital avatar is paused; resume it before chatting");
        }
        ImConversation existing = findExistingDm(userId, null, agent.agentId());
        if (existing != null) {
            revealHiddenConversation(existing.getId(), userId);
            return toVO(existing, userId);
        }
        ImConversation conversation = new ImConversation();
        conversation.setType(ImConversationType.DM);
        conversation.setTitle(agent.name());
        conversation.setOwnerUserId(userId);
        conversation.setStatus(ImConversationStatus.ACTIVE);
        conversation.setLastSeq(0L);
        conversationMapper.insert(conversation);

        addParticipantInternal(conversation.getId(), userId, ImParticipantType.USER,
                ImParticipantRole.OWNER, displayName());
        addParticipantInternal(conversation.getId(), agent.agentId(), ImParticipantType.AGENT,
                ImParticipantRole.BOT, agent.name());

        bootstrapConversation(conversation, userId, ImConversationType.DM);
        publishToMembersAfterCommit(conversation.getId(), ImStreamEvent.builder()
                .type(ImStreamEventType.CONVERSATION_CREATED)
                .conversationId(conversation.getId())
                .title(conversation.getTitle())
                .timestamp(System.currentTimeMillis())
                .build());
        return toVO(conversation, userId);
    }

    /**
     * Reuse an existing one-to-one conversation instead of creating a duplicate.
     * The read is owner-bypassed because the other participant may have created it.
     * A null {@code otherUserId} means the user is chatting with themselves.
     */
    private ImConversation findExistingDm(Long userId, Long otherUserId, Long otherAgentId) {
        List<Long> conversationIds = systemRead(() -> participantMapper.selectList(
                        Wrappers.<ImParticipant>lambdaQuery()
                                .eq(ImParticipant::getParticipantType, ImParticipantType.USER)
                                .eq(ImParticipant::getParticipantId, userId)
                                .select(ImParticipant::getConversationId)))
                .stream().map(ImParticipant::getConversationId)
                .filter(Objects::nonNull).distinct().toList();
        if (conversationIds.isEmpty()) {
            return null;
        }
        List<ImConversation> candidates = systemRead(() -> conversationMapper.selectList(
                Wrappers.<ImConversation>lambdaQuery()
                        .in(ImConversation::getId, conversationIds)
                        .eq(ImConversation::getType, ImConversationType.DM)
                        .orderByDesc(ImConversation::getLastMessageAt)
                        .orderByDesc(ImConversation::getId)));
        for (ImConversation candidate : candidates) {
            List<ImParticipant> members = systemRead(() -> participantMapper.selectList(
                    Wrappers.<ImParticipant>lambdaQuery()
                            .eq(ImParticipant::getConversationId, candidate.getId())));
            Set<Long> userIds = members.stream()
                    .filter(member -> ImParticipantType.USER.equals(member.getParticipantType()))
                    .map(ImParticipant::getParticipantId)
                    .filter(Objects::nonNull).collect(Collectors.toSet());
            Set<Long> agentIds = members.stream()
                    .filter(member -> ImParticipantType.AGENT.equals(member.getParticipantType()))
                    .map(ImParticipant::getParticipantId)
                    .filter(Objects::nonNull).collect(Collectors.toSet());
            if (otherAgentId != null) {
                if (members.size() == 2 && userIds.equals(Set.of(userId))
                        && agentIds.equals(Set.of(otherAgentId))) {
                    return candidate;
                }
            } else if (otherUserId == null) {
                if (members.size() == 1 && userIds.contains(userId)) {
                    return candidate;
                }
            } else if (agentIds.isEmpty() && userIds.size() == 2 && userIds.contains(userId)
                    && userIds.contains(otherUserId)) {
                return candidate;
            }
        }
        return null;
    }

    private SuperAgentDirectorySpi requireDirectory() {
        SuperAgentDirectorySpi directory = directoryPortProvider.getIfAvailable();
        if (directory == null) {
            throw new BizException(CommonErrorCode.PARAM_INVALID,
                    "super-agent directory is unavailable");
        }
        return directory;
    }

    /** Creates the thread (and group-router session for GROUP) at conversation creation time. */
    private void bootstrapConversation(ImConversation conversation, Long creatorUserId, String type) {
        GroupChatBootstrapSpi bootstrap = groupChatBootstrapProvider.getIfAvailable();
        if (bootstrap == null) {
            throw new BizException(CommonErrorCode.PARAM_INVALID,
                    "group chat bootstrap is unavailable");
        }
        String username = AuthContext.getUsername();
        GroupChatBootstrapSpi.GroupChatBootstrapResult result = bootstrap.bootstrap(
                new GroupChatBootstrapSpi.GroupChatBootstrapCommand(
                        conversation.getId(), type, conversation.getTitle(), conversation.getPurpose(),
                        creatorUserId, username, TenantScopeHolder.currentTenantId()));
        conversation.setThreadId(result.threadId());
        conversationMapper.updateById(conversation);
    }

    private ImParticipant addParticipantInternal(Long conversationId, Long participantId,
                                                 String participantType, String role, String displayName) {
        ImParticipant existing = systemRead(() -> participantMapper.selectOne(Wrappers.<ImParticipant>lambdaQuery()
                .eq(ImParticipant::getConversationId, conversationId)
                .eq(ImParticipant::getParticipantType, participantType)
                .eq(ImParticipant::getParticipantId, participantId)
                .last("LIMIT 1")));
        if (existing != null) {
            return existing;
        }
        ImParticipant participant = new ImParticipant();
        participant.setConversationId(conversationId);
        participant.setParticipantType(participantType);
        participant.setParticipantId(participantId);
        participant.setRole(role);
        participant.setDisplayName(displayName);
        participant.setMuted(false);
        participant.setAutoAdded(false);
        participant.setJoinedAt(LocalDateTime.now());
        try {
            participantMapper.insert(participant);
        } catch (DuplicateKeyException e) {
            return systemRead(() -> participantMapper.selectOne(Wrappers.<ImParticipant>lambdaQuery()
                    .eq(ImParticipant::getConversationId, conversationId)
                    .eq(ImParticipant::getParticipantType, participantType)
                    .eq(ImParticipant::getParticipantId, participantId)
                    .last("LIMIT 1")));
        }
        return participant;
    }

    private ConversationVO toVO(ImConversation conversation, Long userId) {
        return toVO(conversation, userId, findSetting(conversation.getId(), userId));
    }

    private ConversationVO toVO(ImConversation conversation, Long userId,
                                ImConversationSetting setting) {
        ConversationVO vo = new ConversationVO();
        vo.setId(conversation.getId());
        vo.setType(conversation.getType());
        vo.setTitle(conversation.getTitle());
        vo.setPurpose(conversation.getPurpose());
        vo.setBizType(conversation.getBizType());
        vo.setBizId(conversation.getBizId());
        vo.setBizPurpose(conversation.getBizPurpose());
        vo.setBizKey(conversation.getBizKey());
        vo.setLocale(conversation.getLocale());
        vo.setOwnerUserId(conversation.getOwnerUserId());
        vo.setThreadId(conversation.getThreadId());
        vo.setLastSeq(conversation.getLastSeq());
        vo.setStatus(conversation.getStatus());
        vo.setCreatedAt(conversation.getCreatedAt() == null ? null
                : conversation.getCreatedAt().format(DTF));
        vo.setLastMessageAt(conversation.getLastMessageAt() == null ? null
                : conversation.getLastMessageAt().format(DTF));
        vo.setPinned(isPinned(setting));
        vo.setMuted(setting != null && Boolean.TRUE.equals(setting.getMuted()));
        vo.setRemark(setting == null ? null : setting.getRemark());
        if (conversation.getLastMessageId() != null) {
            ImMessage last = systemRead(() -> messageMapper.selectById(conversation.getLastMessageId()));
            if (last != null) {
                vo.setLastMessage(toMessageVO(last));
            }
        }
        if (conversation.getPinnedMessageId() != null) {
            ImMessage pinned = systemRead(() -> messageMapper.selectById(conversation.getPinnedMessageId()));
            if (pinned != null && !ImMessageStatus.RECALLED.equals(pinned.getStatus())) {
                vo.setPinnedMessage(toMessageVO(pinned));
            }
        }
        vo.setUnread(unread(conversation, userId));
        List<ImParticipant> participants = systemRead(() -> participantMapper.selectList(
                Wrappers.<ImParticipant>lambdaQuery()
                        .eq(ImParticipant::getConversationId, conversation.getId())
                        .orderByAsc(ImParticipant::getId)));
        Map<Long, UserIdentityVO> identities = identitiesOf(participants);
        Map<Long, Long> agentOwners = agentOwnersOf(participants);
        vo.setParticipants(participants.stream()
                .map(participant -> toParticipantVO(participant, identities, agentOwners))
                .toList());
        vo.setMyRole(roleOf(participants, userId));
        vo.setAllowMemberInvite(allowMemberInvite(conversation));
        ConversationDisplay display = resolveDisplay(conversation, userId, participants, identities);
        vo.setDisplayName(display.displayName());
        vo.setAvatar(display.avatar());
        vo.setSelfConversation(display.selfConversation());
        return vo;
    }

    private static boolean isPinned(ImConversationSetting setting) {
        return setting != null && Boolean.TRUE.equals(setting.getPinned());
    }

    private static boolean isHidden(ImConversationSetting setting) {
        return setting != null && Boolean.TRUE.equals(setting.getHidden());
    }

    /**
     * Deleting a conversation only hides it for the current user. Reusing the same DM
     * (avatar "start chat" or a direct chat with a contact) must make it visible again,
     * otherwise the conversation stays out of the list even though the chat page opens.
     */
    private void revealHiddenConversation(Long conversationId, Long userId) {
        ImConversationSetting setting = findSetting(conversationId, userId);
        if (!isHidden(setting)) {
            return;
        }
        setting.setHidden(false);
        settingMapper.updateById(setting);
        publishToUserAfterCommit(userId, ImStreamEvent.builder()
                .type(ImStreamEventType.CONVERSATION_CREATED)
                .conversationId(conversationId)
                .timestamp(System.currentTimeMillis())
                .build());
    }

    private ImConversationSetting findSetting(Long conversationId, Long userId) {
        return systemRead(() -> settingMapper.selectOne(Wrappers.<ImConversationSetting>lambdaQuery()
                .eq(ImConversationSetting::getConversationId, conversationId)
                .eq(ImConversationSetting::getUserId, userId)
                .last("LIMIT 1")));
    }

    private Map<Long, ImConversationSetting> settingsOf(Long userId) {
        List<ImConversationSetting> settings = systemRead(() -> settingMapper.selectList(
                Wrappers.<ImConversationSetting>lambdaQuery()
                        .eq(ImConversationSetting::getUserId, userId)));
        return settings.stream().collect(Collectors.toMap(
                ImConversationSetting::getConversationId, setting -> setting, (left, right) -> left));
    }

    private Map<Long, ImConversationSetting> settingsOf(Long userId, List<Long> conversationIds) {
        if (conversationIds.isEmpty()) {
            return Map.of();
        }
        List<ImConversationSetting> settings = systemRead(() -> settingMapper.selectList(
                Wrappers.<ImConversationSetting>lambdaQuery()
                        .eq(ImConversationSetting::getUserId, userId)
                        .in(ImConversationSetting::getConversationId, conversationIds)));
        return settings.stream().collect(Collectors.toMap(
                ImConversationSetting::getConversationId, setting -> setting, (left, right) -> left));
    }

    private static ConversationSettingVO toSettingVO(Long conversationId, ImConversationSetting setting) {
        ConversationSettingVO vo = new ConversationSettingVO();
        vo.setConversationId(conversationId);
        vo.setPinned(isPinned(setting));
        vo.setMuted(setting != null && Boolean.TRUE.equals(setting.getMuted()));
        vo.setHidden(setting != null && Boolean.TRUE.equals(setting.getHidden()));
        vo.setRemark(setting == null ? null : setting.getRemark());
        return vo;
    }

    private void publishToMembersAfterCommit(Long conversationId, ImStreamEvent event) {
        afterCommit(() -> deliverToMembers(conversationId, event));
    }

    private void publishToUserAfterCommit(Long userId, ImStreamEvent event) {
        afterCommit(() -> streamService.publish(userId, event));
    }

    /**
     * Publish after the surrounding transaction commits. Otherwise consumers can
     * receive an event and query the database before the new rows are visible.
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

    private void deliverToMembers(Long conversationId, ImStreamEvent event) {
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

    private Map<Long, UserIdentityVO> identitiesOf(List<ImParticipant> participants) {
        List<Long> userIds = participants.stream()
                .filter(participant -> ImParticipantType.USER.equals(participant.getParticipantType()))
                .map(ImParticipant::getParticipantId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        if (userIds.isEmpty()) {
            return Map.of();
        }
        try {
            // Resolve identities outside the caller's self-only scope: a DM/group counterpart may
            // belong to another tenant branch, and a missing identity makes the UI fall back to the
            // stored title (which can be the current user's own name).
            return systemRead(() -> {
                Map<Long, UserIdentityVO> identities = userSpi.identityMap(userIds);
                attachSsoLabels(identities, userIds);
                return identities;
            });
        } catch (Exception e) {
            log.warn("Failed to load participant identities", e);
            return Map.of();
        }
    }

    /** Owner user id per AGENT participant; empty when the directory is unavailable. */
    private Map<Long, Long> agentOwnersOf(List<ImParticipant> participants) {
        List<Long> agentIds = participants.stream()
                .filter(participant -> ImParticipantType.AGENT.equals(participant.getParticipantType()))
                .map(ImParticipant::getParticipantId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        if (agentIds.isEmpty()) {
            return Map.of();
        }
        try {
            SuperAgentDirectorySpi directory = directoryPortProvider.getIfAvailable();
            return directory == null ? Map.of() : directory.ownerUserIds(agentIds);
        } catch (Exception e) {
            log.warn("Failed to load avatar owners", e);
            return Map.of();
        }
    }

    /** Workspace/console share one human label: provider@external-username for SSO users. */
    private void attachSsoLabels(Map<Long, UserIdentityVO> identities, List<Long> userIds) {
        if (identities == null || identities.isEmpty()) {
            return;
        }
        Map<Long, SsoUserLabelVO> labels = ssoIdentitySpi.labelsByUserIds(userIds);
        if (labels == null || labels.isEmpty()) {
            return;
        }
        identities.forEach((userId, identity) -> {
            SsoUserLabelVO label = labels.get(userId);
            if (label == null) {
                return;
            }
            identity.setSsoProviderCode(label.getProviderCode());
            identity.setSsoSubject(label.getSubject());
            identity.setDisplayName(label.getProviderCode() + "@" + label.getSubject());
        });
    }

    private long unread(ImConversation conversation, Long userId) {
        long lastSeq = conversation.getLastSeq() == null ? 0L : conversation.getLastSeq();
        ImReadCursor cursor = readCursorMapper.selectOne(Wrappers.<ImReadCursor>lambdaQuery()
                .eq(ImReadCursor::getConversationId, conversation.getId())
                .eq(ImReadCursor::getUserId, userId)
                .eq(ImReadCursor::getDeviceId, DEFAULT_DEVICE)
                .last("LIMIT 1"));
        long read = cursor == null || cursor.getLastReadSeq() == null ? 0L : cursor.getLastReadSeq();
        return Math.max(0L, lastSeq - read);
    }

    private MessageVO toMessageVO(ImMessage message) {
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

    private ParticipantVO toParticipantVO(ImParticipant participant, Map<Long, UserIdentityVO> identities,
                                          Map<Long, Long> agentOwners) {
        ParticipantVO vo = new ParticipantVO();
        vo.setId(participant.getId());
        vo.setConversationId(participant.getConversationId());
        vo.setParticipantType(participant.getParticipantType());
        vo.setParticipantId(participant.getParticipantId());
        vo.setRole(participant.getRole());
        vo.setDisplayName(participant.getDisplayName());
        if (ImParticipantType.USER.equals(participant.getParticipantType())) {
            UserIdentityVO identity = participant.getParticipantId() == null
                    ? null : identities.get(participant.getParticipantId());
            if (identity != null) {
                vo.setUsername(identity.getUsername());
                vo.setAvatar(identity.getAvatar());
                vo.setDisplayName(currentDisplayName(identity, participant.getDisplayName()));
                vo.setSsoProviderCode(identity.getSsoProviderCode());
                vo.setSsoSubject(identity.getSsoSubject());
            }
        }
        vo.setMuted(participant.getMuted());
        vo.setAutoAdded(Boolean.TRUE.equals(participant.getAutoAdded()));
        if (ImParticipantType.AGENT.equals(participant.getParticipantType())
                && participant.getParticipantId() != null) {
            vo.setOwnerUserId(agentOwners.get(participant.getParticipantId()));
        }
        vo.setJoinedAt(participant.getJoinedAt() == null ? null
                : participant.getJoinedAt().format(DTF));
        return vo;
    }

    /** Resolved counterpart name/avatar for a conversation row or detail response. */
    record ConversationDisplay(String displayName, String avatar, boolean selfConversation) {
    }

    static ConversationDisplay resolveDisplay(ImConversation conversation, Long userId,
                                              List<ImParticipant> members,
                                              Map<Long, UserIdentityVO> identities) {
        return resolveDisplay(conversation, userId, members, identities,
                AuthContext.getDisplayName(), AuthContext.getUsername());
    }

    /**
     * DM: show the other user (or the agent). Two-person group: the workspace names the group
     * after the invitee, so when the title is the current user's own name, show the other member.
     * Larger groups and custom titles keep using the stored title.
     */
    static ConversationDisplay resolveDisplay(ImConversation conversation, Long userId,
                                              List<ImParticipant> members,
                                              Map<Long, UserIdentityVO> identities,
                                              String selfDisplayName,
                                              String selfUsername) {
        if (ImConversationType.DM.equals(conversation.getType())) {
            boolean hasAgent = members.stream()
                    .anyMatch(member -> ImParticipantType.AGENT.equals(member.getParticipantType()));
            ImParticipant other = members.stream()
                    .filter(member -> ImParticipantType.USER.equals(member.getParticipantType()))
                    .filter(member -> !userId.equals(member.getParticipantId()))
                    .findFirst().orElse(null);
            if (other != null) {
                UserIdentityVO identity = identities.get(other.getParticipantId());
                return new ConversationDisplay(currentDisplayName(identity, other.getDisplayName()),
                        identity == null ? null : identity.getAvatar(), false);
            }
            if (!hasAgent) {
                return new ConversationDisplay(null, null, true);
            }
            String agentName = members.stream()
                    .filter(member -> ImParticipantType.AGENT.equals(member.getParticipantType()))
                    .map(ImParticipant::getDisplayName)
                    .filter(Objects::nonNull)
                    .findFirst().orElse(conversation.getTitle());
            return new ConversationDisplay(agentName, null, false);
        }

        long userCount = members.stream()
                .filter(member -> ImParticipantType.USER.equals(member.getParticipantType()))
                .count();
        List<ImParticipant> otherUsers = members.stream()
                .filter(member -> ImParticipantType.USER.equals(member.getParticipantType()))
                .filter(member -> !userId.equals(member.getParticipantId()))
                .toList();
        if (userCount == 2 && otherUsers.size() == 1
                && isSelfTitle(conversation.getTitle(), selfDisplayName, selfUsername)) {
            ImParticipant other = otherUsers.get(0);
            UserIdentityVO identity = identities.get(other.getParticipantId());
            return new ConversationDisplay(currentDisplayName(identity, other.getDisplayName()),
                    identity == null ? null : identity.getAvatar(), false);
        }
        return new ConversationDisplay(null, null, false);
    }

    private static boolean isSelfTitle(String title, String selfDisplayName, String selfUsername) {
        if (title == null || title.isBlank()) {
            return true;
        }
        return title.equals(selfDisplayName) || title.equals(selfUsername);
    }

    private static String currentDisplayName(UserIdentityVO identity, String fallback) {
        if (identity == null) {
            return fallback;
        }
        if (identity.getSsoProviderCode() != null && !identity.getSsoProviderCode().isBlank()
                && identity.getSsoSubject() != null && !identity.getSsoSubject().isBlank()) {
            return identity.getSsoProviderCode() + "@" + identity.getSsoSubject();
        }
        if (identity.getDisplayName() != null && !identity.getDisplayName().isBlank()) {
            return identity.getDisplayName();
        }
        if (identity.getEnglishName() != null && !identity.getEnglishName().isBlank()) {
            return identity.getEnglishName();
        }
        if (identity.getUsername() != null && !identity.getUsername().isBlank()) {
            return identity.getUsername();
        }
        return fallback;
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

    private ImConversation requireConversation(Long conversationId) {
        ImConversation conversation = systemRead(() -> conversationMapper.selectById(conversationId));
        if (conversation == null) {
            throw new BizException(CommonErrorCode.PARAM_INVALID, "conversation not found");
        }
        return conversation;
    }

    /**
     * IM visibility is determined by participants, not by created_by (rows written by the Agent or
     * other members have a created_by that is not the current user). Internal reads therefore
     * bypass DataPermission uniformly, then verify membership explicitly.
     */
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

    private void assertMember(Long conversationId, Long userId) {
        if (!isMember(conversationId, userId)) {
            throw new BizException(CommonErrorCode.FORBIDDEN);
        }
    }

    private void assertAdmin(Long conversationId, Long userId) {
        if (AuthContext.isSuperAdmin()) {
            return;
        }
        ImParticipant participant = systemRead(() -> participantMapper.selectOne(Wrappers.<ImParticipant>lambdaQuery()
                .eq(ImParticipant::getConversationId, conversationId)
                .eq(ImParticipant::getParticipantType, ImParticipantType.USER)
                .eq(ImParticipant::getParticipantId, userId)
                .last("LIMIT 1")));
        if (participant == null
                || (!ImParticipantRole.OWNER.equals(participant.getRole())
                && !ImParticipantRole.ADMIN.equals(participant.getRole()))) {
            throw new BizException(CommonErrorCode.FORBIDDEN);
        }
    }

    private void assertOwner(Long conversationId, Long userId) {
        if (AuthContext.isSuperAdmin()) {
            return;
        }
        ImParticipant participant = systemRead(() -> participantMapper.selectOne(Wrappers.<ImParticipant>lambdaQuery()
                .eq(ImParticipant::getConversationId, conversationId)
                .eq(ImParticipant::getParticipantType, ImParticipantType.USER)
                .eq(ImParticipant::getParticipantId, userId)
                .last("LIMIT 1")));
        if (participant == null || !ImParticipantRole.OWNER.equals(participant.getRole())) {
            throw new BizException(CommonErrorCode.FORBIDDEN);
        }
    }

    /** Admins may add users and avatars; members may add users only when the invite switch is on. */
    private void assertCanAddParticipant(ImConversation conversation, Long userId, String type) {
        if (AuthContext.isSuperAdmin()) {
            return;
        }
        ImParticipant participant = systemRead(() -> participantMapper.selectOne(Wrappers.<ImParticipant>lambdaQuery()
                .eq(ImParticipant::getConversationId, conversation.getId())
                .eq(ImParticipant::getParticipantType, ImParticipantType.USER)
                .eq(ImParticipant::getParticipantId, userId)
                .last("LIMIT 1")));
        if (participant == null) {
            throw new BizException(CommonErrorCode.FORBIDDEN);
        }
        if (ImParticipantRole.OWNER.equals(participant.getRole())
                || ImParticipantRole.ADMIN.equals(participant.getRole())) {
            return;
        }
        if (ImParticipantRole.MEMBER.equals(participant.getRole())
                && ImParticipantType.USER.equals(type)
                && allowMemberInvite(conversation)) {
            return;
        }
        throw new BizException(CommonErrorCode.FORBIDDEN);
    }

    private static String roleOf(List<ImParticipant> participants, Long userId) {
        if (participants == null || userId == null) {
            return null;
        }
        for (ImParticipant participant : participants) {
            if (ImParticipantType.USER.equals(participant.getParticipantType())
                    && userId.equals(participant.getParticipantId())) {
                return participant.getRole();
            }
        }
        return null;
    }

    /** Missing legacy field means the switch is on. */
    private static boolean allowMemberInvite(ImConversation conversation) {        if (conversation == null || conversation.getGateConfig() == null
                || conversation.getGateConfig().isBlank()) {
            return true;
        }
        try {
            ImGateConfig config = JsonUtils.parse(conversation.getGateConfig(), ImGateConfig.class);
            return config == null || config.getAllowMemberInvite() == null
                    || config.getAllowMemberInvite();
        } catch (Exception e) {
            return true;
        }
    }

    private static Long currentUserId() {
        Long userId = AuthContext.getUserId();
        if (userId == null) {
            throw new BizException(CommonErrorCode.UNAUTHORIZED);
        }
        return userId;
    }

    private static String displayName() {
        String name = AuthContext.getDisplayName();
        return name == null || name.isBlank() ? AuthContext.getUsername() : name;
    }
}
