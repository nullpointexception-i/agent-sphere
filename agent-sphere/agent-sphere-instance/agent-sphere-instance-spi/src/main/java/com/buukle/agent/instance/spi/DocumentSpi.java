package com.buukle.agent.instance.spi;

import com.buukle.agent.common.util.PageResult;
import com.buukle.agent.instance.dtvo.dto.CreateDocumentCommentDTO;
import com.buukle.agent.instance.dtvo.dto.CreateDocumentFolderDTO;
import com.buukle.agent.instance.dtvo.dto.UpdateDocumentCommentDTO;
import com.buukle.agent.instance.dtvo.dto.UpdateDocumentFolderDTO;
import com.buukle.agent.instance.dtvo.vo.DocumentCommentVO;
import com.buukle.agent.instance.dtvo.vo.DocumentFolderVO;
import com.buukle.agent.instance.dtvo.vo.DocumentSummaryVO;
import com.buukle.agent.instance.dtvo.vo.DocumentVO;

import java.util.List;

public interface DocumentSpi {
    Long create(DocumentVO vo);

    DocumentVO getById(Long id);

    List<DocumentVO> listBySession(Long sessionId, int page, int size);

    List<DocumentVO> listByInstanceAndCreator(Long instanceId, String createdBy, int page, int size);

    PageResult<DocumentVO> listAll(int page, int size);

    /** Documents created by the current user (workspace "My documents"). */
    PageResult<DocumentSummaryVO> listMine(String username, String keyword, Long folderId,
                                           int page, int size);

    /** Tenant documents created by other users (workspace "Team documents"). */
    PageResult<DocumentSummaryVO> listTeam(String username, Long tenantId, String keyword,
                                           int page, int size);

    void update(Long id, String title, String content);

    void delete(Long id);

    void batchDelete(List<Long> ids);

    long countByInstanceAndCreator(Long instanceId, String createdBy);

    List<DocumentVO> searchByTitle(Long instanceId, String createdBy, String keyword, int page, int size);

    String createShareToken(Long documentId);

    DocumentVO getByShareToken(String token);

    List<DocumentFolderVO> listFolders(String username, Long tenantId);

    DocumentFolderVO createFolder(String username, Long tenantId, CreateDocumentFolderDTO dto);

    DocumentFolderVO updateFolder(String username, Long folderId, UpdateDocumentFolderDTO dto);

    void deleteFolder(String username, Long folderId, String mode);

    void moveDocument(Long id, Long folderId);

    List<DocumentCommentVO> listComments(Long documentId);

    DocumentCommentVO createComment(Long documentId, CreateDocumentCommentDTO dto);

    DocumentCommentVO updateComment(Long commentId, UpdateDocumentCommentDTO dto);

    void deleteComment(Long commentId);

    DocumentCommentVO setCommentResolved(Long commentId, boolean resolved);
}
