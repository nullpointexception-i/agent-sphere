package com.buukle.agent.instance.controller;


import com.buukle.agent.common.constant.ApiPrefix;
import com.buukle.agent.common.constant.PermissionCodes;
import com.buukle.agent.common.annotation.RequirePermission;
import com.buukle.agent.common.context.AuthContext;
import com.buukle.agent.common.context.TenantContext;
import com.buukle.agent.common.context.WithTenant;
import com.buukle.agent.common.error.CommonErrorCode;
import com.buukle.agent.common.exception.BizException;
import com.buukle.agent.common.util.BaseController;
import com.buukle.agent.instance.dtvo.dto.CreateDocumentCommentDTO;
import com.buukle.agent.instance.dtvo.dto.CreateDocumentFolderDTO;
import com.buukle.agent.instance.dtvo.dto.MoveDocumentDTO;
import com.buukle.agent.instance.dtvo.dto.UpdateDocumentCommentDTO;
import com.buukle.agent.instance.dtvo.dto.UpdateDocumentDTO;
import com.buukle.agent.instance.dtvo.dto.UpdateDocumentFolderDTO;
import com.buukle.agent.instance.dtvo.enums.DocumentListScope;
import com.buukle.agent.instance.dtvo.vo.DocumentShareVO;
import com.buukle.agent.instance.dtvo.vo.DocumentVO;
import com.buukle.agent.instance.spi.DocumentSpi;
import com.buukle.agent.instance.service.support.SessionAccessGuard;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Objects;

@RestController
@RequestMapping(ApiPrefix.V1 + "/artifacts/documents")
@RequiredArgsConstructor
@WithTenant
public class DocumentController extends BaseController {

    private final DocumentSpi documentSpi;
    private final SessionAccessGuard sessionAccessGuard;

    @GetMapping
    public ResponseEntity<?> list(
            @RequestParam(required = false) Long sessionId,
            @RequestParam(required = false) String scope,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Long folderId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        if (sessionId != null) {
            sessionAccessGuard.requireReadable(sessionId);
            return ok(documentSpi.listBySession(sessionId, page, size));
        }
        if (StringUtils.hasText(scope)) {
            String username = AuthContext.getUsername();
            if (DocumentListScope.MINE.equalsIgnoreCase(scope)) {
                return ok(documentSpi.listMine(username, keyword, folderId, page, size));
            }
            if (DocumentListScope.TEAM.equalsIgnoreCase(scope)) {
                return ok(documentSpi.listTeam(username, TenantContext.get(), keyword, page, size));
            }
            throw new BizException(CommonErrorCode.PARAM_INVALID,
                    "Unknown document scope: " + scope);
        }
        return ok(documentSpi.listAll(page, size));
    }

    @GetMapping("/folders")
    @RequirePermission(PermissionCodes.DOCUMENT_READ)
    public ResponseEntity<?> listFolders() {
        return ok(documentSpi.listFolders(AuthContext.getUsername(), TenantContext.get()));
    }

    @PostMapping("/folders")
    @RequirePermission(PermissionCodes.DOCUMENT_CREATE)
    public ResponseEntity<?> createFolder(@Valid @RequestBody CreateDocumentFolderDTO dto) {
        return created(documentSpi.createFolder(
                AuthContext.getUsername(), TenantContext.get(), dto));
    }

    @PatchMapping("/folders/{id}")
    @RequirePermission(PermissionCodes.DOCUMENT_UPDATE)
    public ResponseEntity<?> updateFolder(@PathVariable Long id,
                                          @RequestBody UpdateDocumentFolderDTO dto) {
        return ok(documentSpi.updateFolder(AuthContext.getUsername(), id, dto));
    }

    @DeleteMapping("/folders/{id}")
    @RequirePermission(PermissionCodes.DOCUMENT_DELETE)
    public ResponseEntity<?> deleteFolder(@PathVariable Long id,
                                          @RequestParam(defaultValue = "move") String mode) {
        documentSpi.deleteFolder(AuthContext.getUsername(), id, mode);
        return ok();
    }

    @GetMapping("/{id}")
    public ResponseEntity<DocumentVO> get(@PathVariable Long id) {
        DocumentVO vo = documentSpi.getById(id);
        if (vo == null) return ResponseEntity.notFound().build();
        if (vo.getSessionId() != null && canUseOwnerSessionGuard(vo)) {
            sessionAccessGuard.requireReadable(vo.getSessionId());
        }
        return ok(vo);
    }

    /**
     * Team pages are readable by the whole tenant, but their session belongs to the
     * author. Only the author/super admin needs the owner-only session check.
     */
    private boolean canUseOwnerSessionGuard(DocumentVO vo) {
        return AuthContext.isSuperAdmin()
                || Objects.equals(vo.getCreatedBy(), AuthContext.getUsername());
    }

    @PatchMapping("/{id}/folder")
    @RequirePermission(PermissionCodes.DOCUMENT_UPDATE)
    public ResponseEntity<?> moveDocument(@PathVariable Long id,
                                          @RequestBody MoveDocumentDTO dto) {
        documentSpi.moveDocument(id, dto.getFolderId());
        return ok();
    }

    @GetMapping("/{id}/comments")
    @RequirePermission(PermissionCodes.DOCUMENT_READ)
    public ResponseEntity<?> listComments(@PathVariable Long id) {
        return ok(documentSpi.listComments(id));
    }

    @PostMapping("/{id}/comments")
    @RequirePermission(PermissionCodes.DOCUMENT_READ)
    public ResponseEntity<?> createComment(@PathVariable Long id,
                                           @Valid @RequestBody CreateDocumentCommentDTO dto) {
        return created(documentSpi.createComment(id, dto));
    }

    @PatchMapping("/comments/{id}")
    public ResponseEntity<?> updateComment(@PathVariable Long id,
                                           @Valid @RequestBody UpdateDocumentCommentDTO dto) {
        return ok(documentSpi.updateComment(id, dto));
    }

    @DeleteMapping("/comments/{id}")
    public ResponseEntity<?> deleteComment(@PathVariable Long id) {
        documentSpi.deleteComment(id);
        return ok();
    }

    @PostMapping("/comments/{id}/resolve")
    public ResponseEntity<?> resolveComment(@PathVariable Long id) {
        return ok(documentSpi.setCommentResolved(id, true));
    }

    @PostMapping("/comments/{id}/reopen")
    public ResponseEntity<?> reopenComment(@PathVariable Long id) {
        return ok(documentSpi.setCommentResolved(id, false));
    }

    @RequirePermission(PermissionCodes.DOCUMENT_SHARE)
    @PostMapping("/{id}/share")
    public ResponseEntity<?> createShare(@PathVariable Long id) {
        String token = documentSpi.createShareToken(id);
        if (token == null) return ResponseEntity.notFound().build();
        return ok(new DocumentShareVO(token));
    }

    @GetMapping("/shared/{token}")
    public ResponseEntity<DocumentVO> getShared(@PathVariable String token) {
        DocumentVO vo = documentSpi.getByShareToken(token);
        return vo != null ? ok(vo) : ResponseEntity.notFound().build();
    }

    @RequirePermission(PermissionCodes.DOCUMENT_UPDATE)
    @PutMapping("/{id}")
    public ResponseEntity<?> update(@PathVariable Long id, @Valid @RequestBody UpdateDocumentDTO dto) {
        documentSpi.update(id, dto.getTitle() != null ? dto.getTitle() : "", dto.getContent() != null ? dto.getContent() : "");
        return ok();
    }

    @RequirePermission(PermissionCodes.DOCUMENT_DELETE)
    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(@PathVariable Long id) {
        documentSpi.delete(id);
        return ok();
    }

    @RequirePermission(PermissionCodes.DOCUMENT_DELETE)
    @DeleteMapping("/batch")
    public ResponseEntity<?> batchDelete(@RequestBody List<Long> ids) {
        documentSpi.batchDelete(ids);
        return ok();
    }
}
