package com.buukle.agent.instance.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.buukle.agent.common.util.PageResult;
import com.buukle.agent.instance.domain.AgentDocument;
import com.buukle.agent.instance.dtvo.vo.DocumentVO;
import com.buukle.agent.instance.repository.AgentDocumentMapper;
import com.buukle.agent.instance.spi.DocumentSpi;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class DocumentServiceImpl implements DocumentSpi {

    private final AgentDocumentMapper mapper;

    @Override
    @Transactional
    public Long create(DocumentVO vo) {
        AgentDocument entity = toEntity(vo);
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
    @Transactional
    public void update(Long id, String title, String content) {
        AgentDocument entity = new AgentDocument();
        entity.setId(id);
        entity.setTitle(title);
        entity.setContent(content);
        mapper.updateById(entity);
    }

    @Override
    @Transactional
    public void delete(Long id) {
        mapper.deleteById(id);
    }

    @Override
    @Transactional
    public void batchDelete(List<Long> ids) {
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
}
