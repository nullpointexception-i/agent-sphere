package com.buukle.agent.project.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.buukle.agent.common.constant.ProjectAssistantConstants;
import com.buukle.agent.common.context.TenantContext;
import com.buukle.agent.common.error.CommonErrorCode;
import com.buukle.agent.common.exception.BizException;
import com.buukle.agent.instance.dtvo.vo.UserIdentityVO;
import com.buukle.agent.instance.spi.UserSpi;
import com.buukle.agent.project.domain.PmProject;
import com.buukle.agent.project.domain.PmProjectMember;
import com.buukle.agent.project.domain.PmRequirementCommentMention;
import com.buukle.agent.project.dtvo.CommentMentionDTO;
import com.buukle.agent.project.dtvo.RequirementCommentMentionVO;
import com.buukle.agent.project.dtvo.enums.CommentMentionStatus;
import com.buukle.agent.project.dtvo.enums.CommentMentionType;
import com.buukle.agent.project.event.RequirementCommentMentionedEvent;
import com.buukle.agent.project.repository.PmProjectMapper;
import com.buukle.agent.project.repository.PmProjectMemberMapper;
import com.buukle.agent.project.repository.PmRequirementCommentMentionMapper;
import com.buukle.agent.superagent.spi.AvatarInfo;
import com.buukle.agent.superagent.spi.SuperAgentDirectorySpi;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/** Persists and resolves structured @ mentions attached to requirement comments. */
@Service
@RequiredArgsConstructor
public class RequirementCommentMentionService {

    private static final int MAX_ERROR_LENGTH = 1900;

    private final PmRequirementCommentMentionMapper mentionMapper;
    private final PmProjectMapper projectMapper;
    private final PmProjectMemberMapper memberMapper;
    private final SuperAgentDirectorySpi directorySpi;
    private final UserSpi userSpi;
    private final ApplicationEventPublisher eventPublisher;

    /**
     * Validate the requested mentions against project membership and persist them.
     *
     * @return one persisted mention per distinct target
     */
    @Transactional
    public List<PmRequirementCommentMention> create(Long projectId, Long requirementId, Long commentId,
                                                    Long actorUserId,
                                                    List<CommentMentionDTO> requested) {
        if (requested == null || requested.isEmpty()) {
            return List.of();
        }
        PmProject project = projectMapper.selectById(projectId);
        if (project == null) {
            throw new BizException(CommonErrorCode.RESOURCE_NOT_FOUND, "project not found");
        }
        Set<Long> memberIds = memberIds(project);
        Long tenantId = TenantContext.get() == null ? project.getTenantId() : TenantContext.get();
        Map<String, PmRequirementCommentMention> distinct = new LinkedHashMap<>();
        for (CommentMentionDTO input : requested) {
            PmRequirementCommentMention mention = validate(projectId, actorUserId, memberIds, input);
            String key = mention.getMentionType() + ":" + mention.getTargetUserId()
                    + ":" + mention.getTargetAgentId();
            distinct.putIfAbsent(key, mention);
        }
        List<PmRequirementCommentMention> persisted = new ArrayList<>(distinct.size());
        for (PmRequirementCommentMention mention : distinct.values()) {
            mention.setTenantId(tenantId);
            mention.setCommentId(commentId);
            boolean avatar = CommentMentionType.AVATAR.value().equals(mention.getMentionType());
            mention.setStatus(avatar ? CommentMentionStatus.PENDING.value()
                    : CommentMentionStatus.SKIPPED.value());
            if (!avatar) {
                mention.setProcessedAt(LocalDateTime.now());
                mention.setRemark("User mentions are displayed inline; no automatic action.");
            }
            mentionMapper.insert(mention);
            persisted.add(mention);
            if (avatar) {
                eventPublisher.publishEvent(new RequirementCommentMentionedEvent(
                        tenantId, projectId, requirementId, commentId, mention.getId(),
                        mention.getMentionType(), mention.getTargetUserId(),
                        mention.getTargetAgentId(), actorUserId));
            }
        }
        return persisted;
    }

    /** Atomically claim one pending mention for processing. */
    public boolean claim(Long mentionId) {
        return mentionMapper.update(null, new LambdaUpdateWrapper<PmRequirementCommentMention>()
                .eq(PmRequirementCommentMention::getId, mentionId)
                .eq(PmRequirementCommentMention::getStatus, CommentMentionStatus.PENDING.value())
                .set(PmRequirementCommentMention::getStatus,
                        CommentMentionStatus.PROCESSING.value())
                .set(PmRequirementCommentMention::getUpdatedAt, LocalDateTime.now())) > 0;
    }

    public PmRequirementCommentMention find(Long mentionId) {
        return mentionMapper.selectById(mentionId);
    }

    public void markProcessed(Long mentionId, Long replyCommentId) {
        mentionMapper.update(null, new LambdaUpdateWrapper<PmRequirementCommentMention>()
                .eq(PmRequirementCommentMention::getId, mentionId)
                .set(PmRequirementCommentMention::getStatus,
                        CommentMentionStatus.PROCESSED.value())
                .set(PmRequirementCommentMention::getReplyCommentId, replyCommentId)
                .set(PmRequirementCommentMention::getProcessedAt, LocalDateTime.now()));
    }

    public void markFailed(Long mentionId, String error) {
        mentionMapper.update(null, new LambdaUpdateWrapper<PmRequirementCommentMention>()
                .eq(PmRequirementCommentMention::getId, mentionId)
                .set(PmRequirementCommentMention::getStatus,
                        CommentMentionStatus.FAILED.value())
                .set(PmRequirementCommentMention::getErrorMessage, truncate(error))
                .set(PmRequirementCommentMention::getProcessedAt, LocalDateTime.now()));
    }

    public Map<Long, List<RequirementCommentMentionVO>> byCommentIds(Collection<Long> commentIds) {
        if (commentIds == null || commentIds.isEmpty()) {
            return Map.of();
        }
        List<PmRequirementCommentMention> mentions = mentionMapper.selectList(
                new LambdaQueryWrapper<PmRequirementCommentMention>()
                        .in(PmRequirementCommentMention::getCommentId, commentIds)
                        .orderByAsc(PmRequirementCommentMention::getId));
        if (mentions.isEmpty()) {
            return Map.of();
        }
        Map<Long, UserIdentityVO> users = identities(mentions.stream()
                .map(PmRequirementCommentMention::getTargetUserId).toList());
        Map<Long, AvatarInfo> avatars = avatars(mentions.stream()
                .map(PmRequirementCommentMention::getTargetAgentId).toList());
        Map<Long, List<RequirementCommentMentionVO>> result = new LinkedHashMap<>();
        for (PmRequirementCommentMention mention : mentions) {
            RequirementCommentMentionVO vo = new RequirementCommentMentionVO();
            vo.setId(mention.getId());
            vo.setMentionType(mention.getMentionType());
            vo.setTargetUserId(mention.getTargetUserId());
            vo.setTargetUserName(displayName(users.get(mention.getTargetUserId())));
            vo.setTargetAgentId(mention.getTargetAgentId());
            AvatarInfo avatar = avatars.get(mention.getTargetAgentId());
            vo.setTargetAgentName(avatar == null ? null : avatar.name());
            vo.setStatus(mention.getStatus());
            result.computeIfAbsent(mention.getCommentId(), key -> new ArrayList<>()).add(vo);
        }
        return result;
    }

    public boolean isProjectMember(Long projectId, Long userId) {
        PmProject project = projectMapper.selectById(projectId);
        if (project == null || userId == null) {
            return false;
        }
        if (Objects.equals(project.getOwnerUserId(), userId)) {
            return true;
        }
        return memberMapper.selectCount(new LambdaQueryWrapper<PmProjectMember>()
                .eq(PmProjectMember::getProjectId, projectId)
                .eq(PmProjectMember::getUserId, userId)) > 0;
    }

    private PmRequirementCommentMention validate(Long projectId, Long actorUserId,
                                                 Set<Long> memberIds, CommentMentionDTO input) {
        if (input == null || !StringUtils.hasText(input.getMentionType())) {
            throw new BizException(CommonErrorCode.PARAM_INVALID, "mentionType is required");
        }
        CommentMentionType type;
        try {
            type = CommentMentionType.valueOf(input.getMentionType().trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new BizException(CommonErrorCode.PARAM_INVALID,
                    "unsupported mention type: " + input.getMentionType());
        }
        PmRequirementCommentMention mention = new PmRequirementCommentMention();
        mention.setMentionType(type.value());
        if (type == CommentMentionType.USER) {
            if (input.getTargetUserId() == null || !memberIds.contains(input.getTargetUserId())) {
                throw new BizException(CommonErrorCode.PARAM_INVALID,
                        "mentioned user is not a project member");
            }
            mention.setTargetUserId(input.getTargetUserId());
            return mention;
        }
        if (input.getTargetAgentId() == null) {
            throw new BizException(CommonErrorCode.PARAM_INVALID, "targetAgentId is required");
        }
        AvatarInfo avatar = directorySpi.findAvatar(input.getTargetAgentId());
        if (avatar == null
                || !ProjectAssistantConstants.STATUS_ACTIVE.equals(avatar.status())
                || avatar.ownerUserId() == null
                || !memberIds.contains(avatar.ownerUserId())) {
            throw new BizException(CommonErrorCode.PARAM_INVALID,
                    "mentioned avatar is not available in this project");
        }
        if (!Objects.equals(actorUserId, avatar.ownerUserId()) && !avatar.publicVisible()) {
            throw new BizException(CommonErrorCode.PARAM_INVALID,
                    "another member's avatar must be public to be mentioned");
        }
        mention.setTargetAgentId(avatar.agentId());
        mention.setTargetUserId(avatar.ownerUserId());
        return mention;
    }

    private Set<Long> memberIds(PmProject project) {
        Set<Long> ids = memberMapper.selectList(new LambdaQueryWrapper<PmProjectMember>()
                        .eq(PmProjectMember::getProjectId, project.getId()))
                .stream().map(PmProjectMember::getUserId).collect(Collectors.toSet());
        if (project.getOwnerUserId() != null) {
            ids.add(project.getOwnerUserId());
        }
        return ids;
    }

    private Map<Long, UserIdentityVO> identities(Collection<Long> userIds) {
        Set<Long> ids = userIds.stream().filter(Objects::nonNull).collect(Collectors.toSet());
        return ids.isEmpty() ? Map.of() : userSpi.identityMap(ids);
    }

    private Map<Long, AvatarInfo> avatars(Collection<Long> agentIds) {
        Set<Long> ids = agentIds.stream().filter(Objects::nonNull).collect(Collectors.toSet());
        Map<Long, AvatarInfo> result = new LinkedHashMap<>();
        for (Long id : ids) {
            AvatarInfo avatar = directorySpi.findAvatar(id);
            if (avatar != null) {
                result.put(id, avatar);
            }
        }
        return result;
    }

    private static String displayName(UserIdentityVO identity) {
        if (identity == null) {
            return null;
        }
        if (StringUtils.hasText(identity.getDisplayName())) {
            return identity.getDisplayName();
        }
        if (StringUtils.hasText(identity.getEnglishName())) {
            return identity.getEnglishName();
        }
        return identity.getUsername();
    }

    private static String truncate(String value) {
        if (value == null) {
            return null;
        }
        return value.length() <= MAX_ERROR_LENGTH ? value : value.substring(0, MAX_ERROR_LENGTH);
    }
}
