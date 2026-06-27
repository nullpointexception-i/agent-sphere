package com.buukle.agent.instance.spi;

import com.buukle.agent.common.util.PageResult;
import com.buukle.agent.instance.dtvo.vo.DocumentVO;

import java.util.List;

public interface DocumentSpi {
    Long create(DocumentVO vo);

    DocumentVO getById(Long id);

    List<DocumentVO> listBySession(Long sessionId);

    List<DocumentVO> listBySession(Long sessionId, int page, int size);

    PageResult<DocumentVO> listAll(int page, int size);

    void update(Long id, String title, String content);

    void delete(Long id);
}
