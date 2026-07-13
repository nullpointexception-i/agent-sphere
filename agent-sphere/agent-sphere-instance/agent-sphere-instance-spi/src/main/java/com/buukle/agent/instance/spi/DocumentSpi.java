package com.buukle.agent.instance.spi;

import com.buukle.agent.common.util.PageResult;
import com.buukle.agent.instance.dtvo.vo.DocumentVO;

import java.util.List;

public interface DocumentSpi {
    Long create(DocumentVO vo);

    DocumentVO getById(Long id);

    List<DocumentVO> listBySession(Long sessionId, int page, int size);

    List<DocumentVO> listByInstanceAndCreator(Long instanceId, String createdBy, int page, int size);

    PageResult<DocumentVO> listAll(int page, int size);

    void update(Long id, String title, String content);

    void delete(Long id);

    void batchDelete(List<Long> ids);

    long countByInstanceAndCreator(Long instanceId, String createdBy);

    List<DocumentVO> searchByTitle(Long instanceId, String createdBy, String keyword, int page, int size);

    String createShareToken(Long documentId);

    DocumentVO getByShareToken(String token);
}
