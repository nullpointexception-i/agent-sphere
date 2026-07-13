package com.buukle.agent.instance.controller;

import com.buukle.agent.common.annotation.RequirePermission;
import com.buukle.agent.common.context.WithTenant;
import com.buukle.agent.common.util.BaseController;
import com.buukle.agent.instance.dtvo.vo.DocumentVO;
import com.buukle.agent.instance.spi.DocumentSpi;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/artifacts/documents")
@RequiredArgsConstructor
@WithTenant
public class DocumentController extends BaseController {

    private final DocumentSpi documentSpi;

    @GetMapping
    public ResponseEntity<?> list(
            @RequestParam(required = false) Long sessionId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        if (sessionId != null) {
            return ok(documentSpi.listBySession(sessionId, page, size));
        }
        return ok(documentSpi.listAll(page, size));
    }

    @GetMapping("/{id}")
    public ResponseEntity<DocumentVO> get(@PathVariable Long id) {
        DocumentVO vo = documentSpi.getById(id);
        return vo != null ? ok(vo) : ResponseEntity.notFound().build();
    }

    @RequirePermission("document:share")
    @PostMapping("/{id}/share")
    public ResponseEntity<Map<String, String>> createShare(@PathVariable Long id) {
        String token = documentSpi.createShareToken(id);
        if (token == null) return ResponseEntity.notFound().build();
        return ok(Map.of("shareToken", token));
    }

    @GetMapping("/shared/{token}")
    public ResponseEntity<DocumentVO> getShared(@PathVariable String token) {
        DocumentVO vo = documentSpi.getByShareToken(token);
        return vo != null ? ok(vo) : ResponseEntity.notFound().build();
    }

    @RequirePermission("document:update")
    @PutMapping("/{id}")
    public ResponseEntity<?> update(@PathVariable Long id, @RequestBody Map<String, String> body) {
        documentSpi.update(id, body.getOrDefault("title", ""), body.getOrDefault("content", ""));
        return ok();
    }

    @RequirePermission("document:delete")
    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(@PathVariable Long id) {
        documentSpi.delete(id);
        return ok();
    }

    @RequirePermission("document:delete")
    @DeleteMapping("/batch")
    public ResponseEntity<?> batchDelete(@RequestBody List<Long> ids) {
        documentSpi.batchDelete(ids);
        return ok();
    }
}
