package com.buukle.agent.instance.service;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.buukle.agent.common.context.AuthContext;
import com.buukle.agent.common.context.TenantScope;
import com.buukle.agent.common.context.TenantScopeHolder;
import com.buukle.agent.common.exception.BizException;
import com.buukle.agent.common.util.PageResult;
import com.buukle.agent.instance.domain.AgentDocument;
import com.buukle.agent.instance.dtvo.vo.DocumentSummaryVO;
import com.buukle.agent.instance.repository.AgentDocumentMapper;
import com.buukle.agent.instance.service.impl.DocumentServiceImpl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DocumentServiceImplTest {

    @Mock
    AgentDocumentMapper mapper;

    @AfterEach
    void clearContext() {
        TenantScopeHolder.clear();
        AuthContext.clear();
    }

    @Test
    void listMineReturnsOnlyCurrentUserDocuments() {
        DocumentServiceImpl service = new DocumentServiceImpl(mapper);
        AgentDocument document = document(10L, "quarterly.md", "elvin");
        when(mapper.selectPage(any(Page.class), any(Wrapper.class)))
                .thenReturn(page(document));

        PageResult<DocumentSummaryVO> result = service.listMine("elvin", null, 1, 20);

        assertEquals(1, result.getRecords().size());
        DocumentSummaryVO summary = result.getRecords().get(0);
        assertEquals("quarterly.md", summary.getTitle());
        assertEquals("elvin", summary.getCreatedBy());
        assertEquals(9, summary.getContentLength());
        assertEquals("# quarter", summary.getPreview());
    }

    @Test
    void listTeamRunsUnderTenantOnlyScopeAndRestoresIt() {
        DocumentServiceImpl service = new DocumentServiceImpl(mapper);
        TenantScope saved = new TenantScope(
                1L, "elvin", 9L, Set.of(9L), true, Set.of(9L));
        TenantScopeHolder.start(saved);
        when(mapper.selectPage(any(Page.class), any(Wrapper.class))).thenAnswer(invocation -> {
            TenantScope active = TenantScopeHolder.get();
            assertEquals(9L, active.currentTenantId());
            assertFalse(active.selfOnly(), "team query must not apply created_by isolation");
            return page(document(11L, "team.md", "testx0002"));
        });

        PageResult<DocumentSummaryVO> result = service.listTeam("elvin", 9L, null, 1, 20);

        assertEquals(1, result.getRecords().size());
        assertTrue(TenantScopeHolder.get().selfOnly(), "previous scope must be restored");
        assertEquals("elvin", TenantScopeHolder.get().username());
    }

    @Test
    void nonOwnerCannotUpdateTeamDocument() {
        DocumentServiceImpl service = new DocumentServiceImpl(mapper);
        AuthContext.setUsername("elvin");
        AuthContext.setSuperAdmin(false);
        when(mapper.selectById(10L)).thenReturn(document(10L, "team.md", "testx0002"));

        assertThrows(BizException.class,
                () -> service.update(10L, "changed", "body"));
    }

    @Test
    void ownerCanUpdateOwnDocument() {
        DocumentServiceImpl service = new DocumentServiceImpl(mapper);
        AuthContext.setUsername("elvin");
        AuthContext.setSuperAdmin(false);
        when(mapper.selectById(10L)).thenReturn(document(10L, "mine.md", "elvin"));

        service.update(10L, "changed", "body");
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
}
