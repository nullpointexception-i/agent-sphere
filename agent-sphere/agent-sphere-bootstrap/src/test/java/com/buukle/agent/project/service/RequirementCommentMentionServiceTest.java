package com.buukle.agent.project.service;

import com.buukle.agent.common.context.TenantContext;
import com.buukle.agent.common.exception.BizException;
import com.buukle.agent.instance.spi.UserSpi;
import com.buukle.agent.project.domain.PmProject;
import com.buukle.agent.project.domain.PmRequirementCommentMention;
import com.buukle.agent.project.dtvo.CommentMentionDTO;
import com.buukle.agent.project.dtvo.enums.CommentMentionStatus;
import com.buukle.agent.project.dtvo.enums.CommentMentionType;
import com.buukle.agent.project.repository.PmProjectMapper;
import com.buukle.agent.project.repository.PmProjectMemberMapper;
import com.buukle.agent.project.repository.PmRequirementCommentMentionMapper;
import com.buukle.agent.superagent.spi.AvatarInfo;
import com.buukle.agent.superagent.spi.SuperAgentDirectorySpi;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Validation and persistence rules for requirement-comment @ mentions. */
@ExtendWith(MockitoExtension.class)
class RequirementCommentMentionServiceTest {

    private static final Long TENANT_ID = 1L;
    private static final Long PROJECT_ID = 10L;
    private static final Long OWNER_ID = 100L;
    private static final Long REQUIREMENT_ID = 20L;
    private static final Long COMMENT_ID = 30L;

    @Mock
    private PmRequirementCommentMentionMapper mentionMapper;
    @Mock
    private PmProjectMapper projectMapper;
    @Mock
    private PmProjectMemberMapper memberMapper;
    @Mock
    private SuperAgentDirectorySpi directorySpi;
    @Mock
    private UserSpi userSpi;
    @Mock
    private ApplicationEventPublisher eventPublisher;

    private RequirementCommentMentionService service;

    @BeforeEach
    void setUp() {
        service = new RequirementCommentMentionService(mentionMapper, projectMapper,
                memberMapper, directorySpi, userSpi, eventPublisher);
        TenantContext.set(TENANT_ID);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void create_rejectsNonMemberUserMention() {
        when(projectMapper.selectById(PROJECT_ID)).thenReturn(project());
        when(memberMapper.selectList(any())).thenReturn(List.of());

        BizException error = assertThrows(BizException.class, () -> service.create(
                PROJECT_ID, REQUIREMENT_ID, COMMENT_ID, OWNER_ID,
                List.of(userMention(999L))));

        assertEquals("mentioned user is not a project member", error.getMessage());
        verify(mentionMapper, never()).insert(any(PmRequirementCommentMention.class));
    }

    @Test
    void create_marksUserMentionSkippedWithoutAutomation() {
        when(projectMapper.selectById(PROJECT_ID)).thenReturn(project());
        when(memberMapper.selectList(any())).thenReturn(List.of());

        List<PmRequirementCommentMention> rows = service.create(
                PROJECT_ID, REQUIREMENT_ID, COMMENT_ID, OWNER_ID,
                List.of(userMention(OWNER_ID)));

        assertEquals(1, rows.size());
        assertEquals(CommentMentionStatus.SKIPPED.value(), rows.get(0).getStatus());
        verify(mentionMapper).insert(rows.get(0));
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void create_enqueuesAvatarMentionForReply() {
        when(projectMapper.selectById(PROJECT_ID)).thenReturn(project());
        when(memberMapper.selectList(any())).thenReturn(List.of());
        when(directorySpi.findAvatar(77L)).thenReturn(new AvatarInfo(
                77L, "Analyst", OWNER_ID, "ACTIVE", true, 5L, 6L));

        List<PmRequirementCommentMention> rows = service.create(
                PROJECT_ID, REQUIREMENT_ID, COMMENT_ID, OWNER_ID,
                List.of(avatarMention(77L)));

        assertEquals(1, rows.size());
        assertEquals(CommentMentionStatus.PENDING.value(), rows.get(0).getStatus());
        assertEquals(OWNER_ID, rows.get(0).getTargetUserId());
        verify(mentionMapper).insert(rows.get(0));
        ArgumentCaptor<Object> event = ArgumentCaptor.forClass(Object.class);
        verify(eventPublisher).publishEvent(event.capture());
    }

    private static PmProject project() {
        PmProject project = new PmProject();
        project.setId(PROJECT_ID);
        project.setTenantId(TENANT_ID);
        project.setOwnerUserId(OWNER_ID);
        return project;
    }

    private static CommentMentionDTO userMention(Long userId) {
        CommentMentionDTO dto = new CommentMentionDTO();
        dto.setMentionType(CommentMentionType.USER.value());
        dto.setTargetUserId(userId);
        return dto;
    }

    private static CommentMentionDTO avatarMention(Long agentId) {
        CommentMentionDTO dto = new CommentMentionDTO();
        dto.setMentionType(CommentMentionType.AVATAR.value());
        dto.setTargetAgentId(agentId);
        return dto;
    }
}
