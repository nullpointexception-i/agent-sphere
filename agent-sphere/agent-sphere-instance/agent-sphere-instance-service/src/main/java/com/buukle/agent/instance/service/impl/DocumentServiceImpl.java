package com.buukle.agent.instance.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.buukle.agent.common.context.AuthContext;
import com.buukle.agent.common.context.TenantContext;
import com.buukle.agent.common.context.TenantScope;
import com.buukle.agent.common.context.TenantScopeHolder;
import com.buukle.agent.common.error.CommonErrorCode;
import com.buukle.agent.common.exception.BizException;
import com.buukle.agent.common.util.PageResult;
import com.buukle.agent.instance.domain.AgentDocument;
import com.buukle.agent.instance.dtvo.vo.DocumentSummaryVO;
import com.buukle.agent.instance.dtvo.vo.DocumentVO;
import com.buukle.agent.instance.repository.AgentDocumentMapper;
import com.buukle.agent.instance.spi.DocumentSpi;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class DocumentServiceImpl implements DocumentSpi {

    private static final int PREVIEW_CHARS = 240;

    private final AgentDocumentMapper mapper;

    @Override
    @Transactional
    public Long create(DocumentVO vo) {
        AgentDocument entity = toEntity(vo);
        entity.setVersion(1);
        mapper.insert(entity);
        return entity.getId();
    }

    @Override
    public DocumentVO getById(Long id) {
        AgentDocument entity = mapper.selectById(id);
        return entity != null ? toVo(entity) : null;
    }

    @Override
    public List<DocumentVO> listBySession(Long sessionId, int page, int size) {
        Page<AgentDocument> p = mapper.selectPage(
                new Page<>(page, size),
                new LambdaQueryWrapper<AgentDocument>()
                        .eq(AgentDocument::getSessionId, sessionId)
                        .orderByDesc(AgentDocument::getUpdatedAt)
        );
        return p.getRecords().stream().map(this::toVo).toList();
    }

    @Override
    public List<DocumentVO> listByInstanceAndCreator(Long instanceId, String createdBy, int page, int size) {
        Page<AgentDocument> p = mapper.selectPage(
                new Page<>(page, size),
                new LambdaQueryWrapper<AgentDocument>()
                        .eq(AgentDocument::getInstanceId, instanceId)
                        .eq(AgentDocument::getCreatedBy, createdBy)
                        .orderByDesc(AgentDocument::getUpdatedAt)
        );
        return p.getRecords().stream().map(this::toVo).toList();
    }

    @Override
    public PageResult<DocumentVO> listAll(int page, int size) {
        Page<AgentDocument> p = mapper.selectPage(
                new Page<>(page, size),
                new LambdaQueryWrapper<AgentDocument>()
                        .orderByDesc(AgentDocument::getUpdatedAt)
        );
        List<DocumentVO> records = p.getRecords().stream().map(this::toVo).toList();
        return new PageResult<>(records, p.getTotal(), page, size);
    }

    @Override
    public PageResult<DocumentSummaryVO> listMine(String username, String keyword, int page, int size) {
        if (username == null || username.isBlank()) {
            throw new BizException(CommonErrorCode.UNAUTHORIZED);
        }
        Page<AgentDocument> p = mapper.selectPage(
                new Page<>(Math.max(1, page), Math.max(1, size)),
                new LambdaQueryWrapper<AgentDocument>()
                        .eq(AgentDocument::getCreatedBy, username)
                        .like(keyword != null && !keyword.isBlank(),
                                AgentDocument::getTitle, keyword)
                        .orderByDesc(AgentDocument::getUpdatedAt)
        );
        return PageResult.of(p.getRecords().stream().map(this::toSummary).toList(),
                p.getTotal(), page, size);
    }

    @Override
    public PageResult<DocumentSummaryVO> listTeam(String username, Long tenantId, String keyword,
                                                  int page, int size) {
        if (username == null || username.isBlank()) {
            throw new BizException(CommonErrorCode.UNAUTHORIZED);
        }
        if (tenantId == null) {
            throw new BizException(CommonErrorCode.FORBIDDEN);
        }
        TenantScope saved = TenantScopeHolder.get();
        try {
            // Team docs are readable by the whole tenant, but they are not personal
            // data: drop selfOnly for this query only, never the tenant condition.
            TenantScopeHolder.start(TenantScope.tenantOnly(tenantId, username));
            Page<AgentDocument> p = mapper.selectPage(
                    new Page<>(Math.max(1, page), Math.max(1, size)),
                    new LambdaQueryWrapper<AgentDocument>()
                            .and(w -> w.ne(AgentDocument::getCreatedBy, username)
                                    .or().isNull(AgentDocument::getCreatedBy))
                            .like(keyword != null && !keyword.isBlank(),
                                    AgentDocument::getTitle, keyword)
                            .orderByDesc(AgentDocument::getUpdatedAt)
            );
            return PageResult.of(p.getRecords().stream().map(this::toSummary).toList(),
                    p.getTotal(), page, size);
        } finally {
            if (saved != null) {
                TenantScopeHolder.start(saved);
            } else {
                TenantScopeHolder.clear();
            }
        }
    }

    @Override
    @Transactional
    public void update(Long id, String title, String content) {
        AgentDocument existing = mapper.selectById(id);
        if (existing == null) {
            return;
        }
        assertCanWrite(existing);
        AgentDocument entity = new AgentDocument();
        entity.setId(id);
        entity.setTitle(title);
        entity.setContent(content);
        entity.setVersion((existing.getVersion() == null ? 0 : existing.getVersion()) + 1);
        mapper.updateById(entity);
    }

    @Override
    @Transactional
    public void delete(Long id) {
        AgentDocument existing = mapper.selectById(id);
        if (existing == null) {
            return;
        }
        assertCanWrite(existing);
        mapper.deleteById(id);
    }

    @Override
    @Transactional
    public void batchDelete(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return;
        }
        for (AgentDocument existing : mapper.selectBatchIds(ids)) {
            assertCanWrite(existing);
        }
        mapper.deleteBatchIds(ids);
    }

    @Override
    public long countByInstanceAndCreator(Long instanceId, String createdBy) {
        return mapper.selectCount(new LambdaQueryWrapper<AgentDocument>()
                .eq(AgentDocument::getInstanceId, instanceId)
                .eq(AgentDocument::getCreatedBy, createdBy));
    }

    @Override
    public List<DocumentVO> searchByTitle(Long instanceId, String createdBy, String keyword, int page, int size) {
        Page<AgentDocument> p = mapper.selectPage(
                new Page<>(page, size),
                new LambdaQueryWrapper<AgentDocument>()
                        .eq(AgentDocument::getInstanceId, instanceId)
                        .eq(AgentDocument::getCreatedBy, createdBy)
                        .like(AgentDocument::getTitle, keyword)
                        .orderByDesc(AgentDocument::getId)
        );
        return p.getRecords().stream().map(this::toVo).toList();
    }

    @Override
    public String createShareToken(Long documentId) {
        AgentDocument doc = mapper.selectById(documentId);
        if (doc == null) return null;
        if (doc.getShareToken() != null) return doc.getShareToken();
        String token = UUID.randomUUID().toString().replace("-", "");
        AgentDocument update = new AgentDocument();
        update.setId(documentId);
        update.setShareToken(token);
        mapper.updateById(update);
        return token;
    }

    @Override
    public DocumentVO getByShareToken(String token) {
        AgentDocument doc = mapper.selectOne(
                new LambdaQueryWrapper<AgentDocument>()
                        .eq(AgentDocument::getShareToken, token));
        return doc != null ? toVo(doc) : null;
    }

    private AgentDocument toEntity(DocumentVO vo) {
        AgentDocument e = new AgentDocument();
        e.setTitle(vo.getTitle());
        e.setContent(vo.getContent());
        e.setContentType(vo.getContentType() != null ? vo.getContentType() : "markdown");
        e.setSessionId(vo.getSessionId());
        e.setInstanceId(vo.getInstanceId());
        e.setRunId(vo.getRunId());
        e.setCreatedBy(vo.getCreatedBy());
        return e;
    }

    private DocumentVO toVo(AgentDocument e) {
        DocumentVO vo = new DocumentVO();
        vo.setId(e.getId());
        vo.setTitle(e.getTitle());
        vo.setContent(e.getContent());
        vo.setContentType(e.getContentType());
        vo.setVersion(e.getVersion());
        vo.setSessionId(e.getSessionId());
        vo.setInstanceId(e.getInstanceId());
        vo.setRunId(e.getRunId());
        vo.setShareToken(e.getShareToken());
        vo.setCreatedBy(e.getCreatedBy());
        vo.setUpdatedBy(e.getUpdatedBy());
        vo.setCreatedAt(e.getCreatedAt());
        vo.setUpdatedAt(e.getUpdatedAt());
        return vo;
    }

    private DocumentSummaryVO toSummary(AgentDocument e) {
        DocumentSummaryVO vo = new DocumentSummaryVO();
        vo.setId(e.getId());
        vo.setTitle(e.getTitle());
        vo.setContentType(e.getContentType());
        vo.setVersion(e.getVersion());
        vo.setSessionId(e.getSessionId());
        vo.setInstanceId(e.getInstanceId());
        vo.setCreatedBy(e.getCreatedBy());
        vo.setCreatedAt(e.getCreatedAt());
        vo.setUpdatedAt(e.getUpdatedAt());
        String content = e.getContent();
        vo.setContentLength(content == null ? 0 : content.length());
        vo.setPreview(content == null ? null
                : content.substring(0, Math.min(PREVIEW_CHARS, content.length())));
        return vo;
    }

    /** Workspace team documents are read-only: only the creator (or a super admin) may write. */
    private void assertCanWrite(AgentDocument document) {
        if (document == null || AuthContext.isSuperAdmin()) {
            return;
        }
        String username = AuthContext.getUsername();
        // Internal runtime calls may not carry a username; they are not user edits.
        if (username == null || username.isBlank()) {
            return;
        }
        if (!Objects.equals(document.getCreatedBy(), username)) {
            throw new BizException(CommonErrorCode.FORBIDDEN);
        }
    }
}
