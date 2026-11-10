package com.buukle.agent.instance.controller;



import com.buukle.agent.common.constant.ApiPrefix;
import com.buukle.agent.common.constant.PermissionCodes;
import com.buukle.agent.common.annotation.RequirePermission;
import com.buukle.agent.common.context.WithTenant;
import com.buukle.agent.common.util.BaseController;
import com.buukle.agent.instance.dtvo.dto.UpdateDocumentDTO;
import com.buukle.agent.instance.dtvo.vo.DocumentShareVO;
import com.buukle.agent.instance.dtvo.vo.DocumentVO;
import com.buukle.agent.instance.spi.DocumentSpi;
import com.buukle.agent.instance.service.support.SessionAccessGuard;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

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
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        if (sessionId != null) {
            sessionAccessGuard.requireReadable(sessionId);
            return ok(documentSpi.listBySession(sessionId, page, size));
        }
        return ok(documentSpi.listAll(page, size));
    }

    @GetMapping("/{id}")
    public ResponseEntity<DocumentVO> get(@PathVariable Long id) {
        DocumentVO vo = documentSpi.getById(id);
        if (vo == null) return ResponseEntity.notFound().build();
        if (vo.getSessionId() != null) {
            sessionAccessGuard.requireReadable(vo.getSessionId());
        }
        return ok(vo);
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
