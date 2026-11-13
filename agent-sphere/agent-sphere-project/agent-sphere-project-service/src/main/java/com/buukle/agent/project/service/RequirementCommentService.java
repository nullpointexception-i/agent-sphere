package com.buukle.agent.project.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.buukle.agent.common.constant.ProjectAssistantConstants;
import com.buukle.agent.common.error.CommonErrorCode;
import com.buukle.agent.common.exception.BizException;
import com.buukle.agent.instance.dtvo.vo.UserIdentityVO;
import com.buukle.agent.instance.spi.UserSpi;
import com.buukle.agent.project.domain.PmRequirement;
import com.buukle.agent.project.domain.PmRequirementComment;
import com.buukle.agent.project.domain.PmRequirementCommentMention;
import com.buukle.agent.project.dtvo.CommentMentionDTO;
import com.buukle.agent.project.dtvo.RequirementAttachmentVO;
import com.buukle.agent.project.dtvo.RequirementCommentMentionVO;
import com.buukle.agent.project.dtvo.RequirementCommentVO;
import com.buukle.agent.project.dtvo.enums.CommentAuthorType;
import com.buukle.agent.project.dtvo.enums.ProjectContextEventType;
import com.buukle.agent.project.event.ProjectRequirementChangedEvent;
import com.buukle.agent.project.repository.PmRequirementCommentMapper;
import com.buukle.agent.project.repository.PmRequirementMapper;
import com.buukle.agent.util.json.JsonUtils;
import com.fasterxml.jackson.core.type.TypeReference;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Creates and reads requirement comments for every author kind (member, assistant, avatar).
 *
 * <p>This service has no request-context dependency: callers pass tenant and author explicitly so
 * the same path serves HTTP requests and background baseline runs.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RequirementCommentService {

    private static final String COMMENT_STATUS_ACTIVE = ProjectAssistantConstants.STATUS_ACTIVE;
    private static final int PREVIEW_LENGTH = 300;

    private final PmRequirementCommentMapper commentMapper;
    private final PmRequirementMapper requirementMapper;
    private final RequirementCommentMentionService mentionService;
    private final ProjectContextEventService contextEventService;
    private final ApplicationEventPublisher eventPublisher;
    private final UserSpi userSpi;

    @Transactional
    public RequirementCommentVO add(RequirementCommentCommand command) {
        if (command == null || command.requirementId() == null) {
            throw new BizException(CommonErrorCode.PARAM_INVALID, "requirementId is required");
        }
        String content = command.content() == null ? "" : command.content().trim();
        boolean hasAttachments = command.attachments() != null && !command.attachments().isEmpty();
        if (content.isEmpty() && !hasAttachments) {
            throw new BizException(CommonErrorCode.PARAM_INVALID,
                    "comment content or attachments are required");
        }
        if (StringUtils.hasText(command.actionKey())) {
            LambdaQueryWrapper<PmRequirementComment> existingQuery =
                    new LambdaQueryWrapper<PmRequirementComment>()
                            .eq(PmRequirementComment::getActionKey, command.actionKey());
            if (command.sourceRunId() != null) {
                existingQuery.eq(PmRequirementComment::getSourceRunId, command.sourceRunId());
            }
            PmRequirementComment existing = commentMapper.selectOne(
                    existingQuery.last("LIMIT 1"));
            if (existing != null) {
                return toVO(existing);
            }
        }
        PmRequirement requirement = requirementMapper.selectById(command.requirementId());
        if (requirement == null
                || !Objects.equals(requirement.getProjectId(), command.projectId())) {
            throw new BizException(CommonErrorCode.RESOURCE_NOT_FOUND,
                    "requirement not found in this project");
        }
        Long tenantId = command.tenantId() == null
                ? requirement.getTenantId() : command.tenantId();
        PmRequirementComment comment = new PmRequirementComment();
        comment.setTenantId(tenantId);
        comment.setRequirementId(requirement.getId());
        comment.setUserId(command.authorUserId());
        comment.setAuthorType(command.authorType());
        comment.setAuthorName(command.authorName());
        comment.setParentCommentId(command.parentCommentId());
        comment.setSourceSessionId(command.sourceSessionId());
        comment.setSourceRunId(command.sourceRunId());
        comment.setSourceBatchId(command.sourceBatchId());
        comment.setActionKey(command.actionKey());
        comment.setContent(content);
        if (hasAttachments) {
            comment.setAttachments(JsonUtils.toJson(command.attachments()));
        }
        comment.setStatus(COMMENT_STATUS_ACTIVE);
        try {
            commentMapper.insert(comment);
        } catch (org.springframework.dao.DuplicateKeyException e) {
            if (StringUtils.hasText(command.actionKey())) {
                PmRequirementComment existing = commentMapper.selectOne(
                        new LambdaQueryWrapper<PmRequirementComment>()
                                .eq(PmRequirementComment::getActionKey, command.actionKey())
                                .last("LIMIT 1"));
                if (existing != null) {
                    return toVO(existing);
                }
            }
            throw e;
        }
        mentionService.create(command.projectId(), requirement.getId(), comment.getId(),
                command.authorUserId(), command.mentions());
        recordContext(requirement, command, content);
        publishChanged(tenantId, requirement.getProjectId(), requirement.getId());
        return toVO(comment);
    }

    public List<RequirementCommentVO> list(Long requirementId) {
        if (requirementId == null) {
            return List.of();
        }
        List<PmRequirementComment> comments = commentMapper.selectList(
                new LambdaQueryWrapper<PmRequirementComment>()
                        .eq(PmRequirementComment::getRequirementId, requirementId)
                        .orderByAsc(PmRequirementComment::getCreatedAt)
                        .orderByAsc(PmRequirementComment::getId));
        if (comments.isEmpty()) {
            return List.of();
        }
        Map<Long, List<RequirementCommentMentionVO>> mentions = mentionService.byCommentIds(
                comments.stream().map(PmRequirementComment::getId).toList());
        Map<Long, UserIdentityVO> identities = identities(comments.stream()
                .map(PmRequirementComment::getUserId).toList());
        return comments.stream().map(comment -> {
            RequirementCommentVO vo = toVO(comment, identities);
            vo.setMentions(mentions.getOrDefault(comment.getId(), List.of()));
            return vo;
        }).toList();
    }

    public RequirementCommentVO toVO(PmRequirementComment comment) {
        RequirementCommentVO vo = toVO(comment, identities(List.of(comment.getUserId())));
        vo.setMentions(mentionService.byCommentIds(List.of(comment.getId()))
                .getOrDefault(comment.getId(), List.of()));
        return vo;
    }

    private RequirementCommentVO toVO(PmRequirementComment comment,
                                      Map<Long, UserIdentityVO> identities) {
        RequirementCommentVO vo = new RequirementCommentVO();
        vo.setId(comment.getId());
        vo.setRequirementId(comment.getRequirementId());
        vo.setUserId(comment.getUserId());
        vo.setUserName(displayName(identities.get(comment.getUserId()), comment));
        vo.setAuthorType(comment.getAuthorType());
        vo.setAuthorName(StringUtils.hasText(comment.getAuthorName())
                ? comment.getAuthorName() : vo.getUserName());
        vo.setParentCommentId(comment.getParentCommentId());
        vo.setContent(comment.getContent());
        vo.setCreatedAt(comment.getCreatedAt());
        if (StringUtils.hasText(comment.getAttachments())) {
            List<RequirementAttachmentVO> attachments = JsonUtils.parse(
                    comment.getAttachments(),
                    new TypeReference<List<RequirementAttachmentVO>>() {
                    });
            vo.setAttachments(attachments == null ? List.of() : attachments);
        }
        return vo;
    }

    private void recordContext(PmRequirement requirement, RequirementCommentCommand command,
                               String content) {
        try {
            boolean analyzable = CommentAuthorType.USER.value().equals(command.authorType());
            String summary = "Requirement " + requirement.getRequirementKey() + " comment: "
                    + preview(content) + " by " + authorLabel(command);
            if (analyzable) {
                contextEventService.record(requirement.getProjectId(), requirement.getId(),
                        ProjectContextEventType.COMMENT.value(), summary,
                        command.sourceSessionId(), null, command.authorUserId());
            } else {
                contextEventService.recordSkipped(requirement.getProjectId(), requirement.getId(),
                        ProjectContextEventType.COMMENT.value(), summary,
                        command.sourceSessionId(), null, command.authorUserId());
            }
        } catch (Exception e) {
            log.warn("Project context record failed: project={}, error={}",
                    requirement.getProjectId(), e.getMessage());
        }
    }

    private void publishChanged(Long tenantId, Long projectId, Long requirementId) {
        try {
            eventPublisher.publishEvent(
                    new ProjectRequirementChangedEvent(tenantId, projectId, requirementId));
        } catch (Exception e) {
            log.warn("Project change event publish failed: project={}, error={}",
                    projectId, e.getMessage());
        }
    }

    private Map<Long, UserIdentityVO> identities(List<Long> userIds) {
        Set<Long> ids = userIds.stream().filter(Objects::nonNull).collect(Collectors.toSet());
        return ids.isEmpty() ? Map.of() : userSpi.identityMap(ids);
    }

    private static String authorLabel(RequirementCommentCommand command) {
        if (StringUtils.hasText(command.authorName())) {
            return command.authorName();
        }
        return CommentAuthorType.USER.value();
    }

    private static String displayName(UserIdentityVO identity, PmRequirementComment comment) {
        if (StringUtils.hasText(comment.getAuthorName())) {
            return comment.getAuthorName();
        }
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

    private static String preview(String content) {
        if (content == null) {
            return "";
        }
        String text = content.replaceAll("\\s+", " ").strip();
        return text.length() <= PREVIEW_LENGTH ? text : text.substring(0, PREVIEW_LENGTH);
    }

    /** Typed input of one comment creation. */
    public record RequirementCommentCommand(
            Long tenantId,
            Long projectId,
            Long requirementId,
            Long authorUserId,
            String authorType,
            String authorName,
            Long parentCommentId,
            String content,
            List<RequirementAttachmentVO> attachments,
            List<CommentMentionDTO> mentions,
            Long sourceSessionId,
            Long sourceRunId,
            String sourceBatchId,
            String actionKey
    ) {
    }
}
