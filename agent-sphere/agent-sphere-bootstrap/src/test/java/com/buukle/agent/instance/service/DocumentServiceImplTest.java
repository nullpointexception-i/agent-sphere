package com.buukle.agent.instance.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.buukle.agent.common.context.AuthContext;
import com.buukle.agent.common.context.TenantContext;
import com.buukle.agent.common.context.TenantScope;
import com.buukle.agent.common.context.TenantScopeHolder;
import com.buukle.agent.common.exception.BizException;
import com.buukle.agent.common.util.PageResult;
import com.buukle.agent.instance.domain.AgentDocument;
import com.buukle.agent.instance.domain.AgentDocumentComment;
import com.buukle.agent.instance.domain.AgentDocumentFolder;
import com.buukle.agent.instance.dtvo.dto.CreateDocumentCommentDTO;
import com.buukle.agent.instance.dtvo.dto.CreateDocumentFolderDTO;
import com.buukle.agent.instance.dtvo.dto.UpdateDocumentCommentDTO;
import com.buukle.agent.instance.dtvo.vo.DocumentCommentVO;
import com.buukle.agent.instance.dtvo.vo.DocumentSummaryVO;
import com.buukle.agent.instance.repository.AgentDocumentCommentMapper;
import com.buukle.agent.instance.repository.AgentDocumentFolderMapper;
import com.buukle.agent.instance.repository.AgentDocumentMapper;
import com.buukle.agent.instance.service.impl.DocumentServiceImpl;
import com.buukle.agent.instance.spi.InstanceSpi;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DocumentServiceImplTest {

    @Mock
    AgentDocumentMapper mapper;
    @Mock
    AgentDocumentFolderMapper folderMapper;
    @Mock
    AgentDocumentCommentMapper commentMapper;
    @Mock
    InstanceSpi instanceSpi;

    @BeforeAll
    static void initMpTableInfo() {
        MybatisConfiguration configuration = new MybatisConfiguration();
        for (Class<?> entity : new Class<?>[] {
                AgentDocument.class, AgentDocumentFolder.class, AgentDocumentComment.class}) {
            TableInfoHelper.initTableInfo(
                    new MapperBuilderAssistant(configuration, ""), entity);
        }
    }

    @AfterEach
    void clearContext() {
        TenantScopeHolder.clear();
        TenantContext.clear();
        AuthContext.clear();
    }

    @Test
    void listMineReturnsOnlyCurrentUserDocuments() {
        DocumentServiceImpl service = service();
        AgentDocument document = document(10L, "quarterly.md", "elvin");
        when(mapper.selectPage(any(Page.class), any(Wrapper.class)))
                .thenReturn(page(document));
        given(commentMapper.countByDocumentIds(any())).willReturn(List.of());

        PageResult<DocumentSummaryVO> result = service.listMine("elvin", null, null, 1, 20);

        assertEquals(1, result.getRecords().size());
        DocumentSummaryVO summary = result.getRecords().get(0);
        assertEquals("quarterly.md", summary.getTitle());
        assertEquals("elvin", summary.getCreatedBy());
        assertEquals(9, summary.getContentLength());
        assertEquals("# quarter", summary.getPreview());
    }

    @Test
    void listTeamRunsUnderTenantOnlyScopeAndRestoresIt() {
        DocumentServiceImpl service = service();
        TenantScope saved = new TenantScope(
                1L, "elvin", 9L, Set.of(9L), true, Set.of(9L));
        TenantScopeHolder.start(saved);
        when(mapper.selectPage(any(Page.class), any(Wrapper.class))).thenAnswer(invocation -> {
            TenantScope active = TenantScopeHolder.get();
            assertEquals(9L, active.currentTenantId());
            assertFalse(active.selfOnly(), "team query must not apply created_by isolation");
            return page(document(11L, "team.md", "testx0002"));
        });
        given(commentMapper.countByDocumentIds(any())).willReturn(List.of());

        PageResult<DocumentSummaryVO> result = service.listTeam("elvin", 9L, null, 1, 20);

        assertEquals(1, result.getRecords().size());
        assertTrue(TenantScopeHolder.get().selfOnly(), "previous scope must be restored");
        assertEquals("elvin", TenantScopeHolder.get().username());
    }

    @Test
    void getByIdReadsAcrossTenantAndRestoresScope() {
        DocumentServiceImpl service = service();
        TenantScope saved = new TenantScope(
                1L, "elvin", 9L, Set.of(9L), true, Set.of(9L));
        TenantScopeHolder.start(saved);
        TenantContext.set(9L);
        AuthContext.setUsername("elvin");
        when(mapper.selectById(10L)).thenAnswer(invocation -> {
            assertFalse(TenantScopeHolder.get().selfOnly(),
                    "wiki page detail must be tenant-scoped");
            return document(10L, "team.md", "testx0002");
        });

        assertNotNull(service.getById(10L));
        assertTrue(TenantScopeHolder.get().selfOnly(), "previous scope must be restored");
    }

    @Test
    void nonOwnerCannotUpdateTeamDocument() {
        DocumentServiceImpl service = service();
        AuthContext.setUsername("elvin");
        AuthContext.setSuperAdmin(false);
        when(mapper.selectById(10L)).thenReturn(document(10L, "team.md", "testx0002"));

        assertThrows(BizException.class,
                () -> service.update(10L, "changed", "body"));
    }

    @Test
    void ownerCanUpdateOwnDocument() {
        DocumentServiceImpl service = service();
        AuthContext.setUsername("elvin");
        AuthContext.setSuperAdmin(false);
        when(mapper.selectById(10L)).thenReturn(document(10L, "mine.md", "elvin"));

        service.update(10L, "changed", "body");
    }

    @Test
    void duplicateFolderNameIsRejected() {
        DocumentServiceImpl service = service();
        AuthContext.setUserId(1L);
        given(folderMapper.selectCount(any())).willReturn(1L);
        CreateDocumentFolderDTO dto = new CreateDocumentFolderDTO();
        dto.setName("合同");

        assertThrows(BizException.class, () -> service.createFolder("elvin", 9L, dto));
        verify(folderMapper, never()).insert(any(AgentDocumentFolder.class));
    }

    @Test
    void teamPageCanBeCommentedByAnotherTenantMember() {
        DocumentServiceImpl service = service();
        AuthContext.setUserId(2L);
        AuthContext.setUsername("elvin");
        AuthContext.setDisplayName("Elvin");
        TenantContext.set(9L);
        when(mapper.selectById(10L)).thenReturn(document(10L, "team.md", "testx0002"));
        given(commentMapper.insert(any(AgentDocumentComment.class))).willAnswer(invocation -> {
            AgentDocumentComment comment = invocation.getArgument(0);
            comment.setId(99L);
            return 1;
        });
        CreateDocumentCommentDTO dto = new CreateDocumentCommentDTO();
        dto.setContent("这个字段要加校验");

        DocumentCommentVO comment = service.createComment(10L, dto);

        assertEquals(99L, comment.getId());
        assertEquals("Elvin", comment.getAuthorName());
        assertTrue(comment.isMine());
        assertFalse(comment.isCanModerate());
    }

    @Test
    void nonAuthorCannotEditComment() {
        DocumentServiceImpl service = service();
        AuthContext.setUserId(2L);
        AuthContext.setUsername("elvin");
        AgentDocumentComment comment = comment(5L, 10L, "testx0002");
        when(commentMapper.selectById(5L)).thenReturn(comment);
        UpdateDocumentCommentDTO dto = new UpdateDocumentCommentDTO();
        dto.setContent("changed");

        assertThrows(BizException.class, () -> service.updateComment(5L, dto));
    }

    @Test
    void resolvingRequiresPageOwnerOrAdmin() {
        DocumentServiceImpl service = service();
        AuthContext.setUserId(2L);
        AuthContext.setUsername("elvin");
        TenantContext.set(9L);
        AgentDocumentComment comment = comment(5L, 10L, "testx0002");
        when(commentMapper.selectById(5L)).thenReturn(comment);
        when(mapper.selectById(10L)).thenReturn(document(10L, "team.md", "testx0002"));

        assertThrows(BizException.class, () -> service.setCommentResolved(5L, true));
    }

    private DocumentServiceImpl service() {
        return new DocumentServiceImpl(mapper, folderMapper, commentMapper, instanceSpi);
    }

    private static Page<AgentDocument> page(AgentDocument document) {
        Page<AgentDocument> page = new Page<>(1, 20);
        page.setRecords(List.of(document));
        page.setTotal(1);
        return page;
    }

    private static AgentDocument document(Long id, String title, String createdBy) {
        AgentDocument document = new AgentDocument();
        document.setId(id);
        document.setTitle(title);
        document.setCreatedBy(createdBy);
        document.setContent("# quarter");
        document.setContentType("markdown");
        document.setVersion(1);
        return document;
    }

    private static AgentDocumentComment comment(Long id, Long documentId, String createdBy) {
        AgentDocumentComment comment = new AgentDocumentComment();
        comment.setId(id);
        comment.setDocumentId(documentId);
        comment.setCreatedBy(createdBy);
        comment.setAuthorName(createdBy);
        comment.setContent("comment");
        comment.setStatus("OPEN");
        return comment;
    }
}
