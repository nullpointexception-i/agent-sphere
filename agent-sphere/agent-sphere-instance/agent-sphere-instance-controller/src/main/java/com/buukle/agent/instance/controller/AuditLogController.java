package com.buukle.agent.instance.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.buukle.agent.common.annotation.RequirePermission;
import com.buukle.agent.common.util.BaseController;
import com.buukle.agent.instance.dtvo.dto.FrontendTrackDTO;
import com.buukle.agent.instance.dtvo.vo.AuditLogVO;
import com.buukle.agent.instance.service.AuditLogService;
import com.buukle.agent.instance.service.AuditQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.List;

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class AuditLogController extends BaseController {

    private static final int MAX_FRONTEND_EVENTS = 100;

    private final AuditQueryService auditQueryService;
    private final AuditLogService auditLogService;

    @RequirePermission("admin:audit-log:read")
    @GetMapping("/admin/audit-logs")
    public ResponseEntity<Page<AuditLogVO>> list(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String username,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) String resourceType,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime endDate) {
        return ok(auditQueryService.listPage(page, size, username, action, resourceType, startDate, endDate));
    }

    @PostMapping("/track/frontend")
    public ResponseEntity<?> trackFrontend(@RequestBody List<FrontendTrackDTO> events) {
        if (events == null) return ok();
        if (events.size() > MAX_FRONTEND_EVENTS) {
            events = events.subList(0, MAX_FRONTEND_EVENTS);
        }
        auditLogService.recordFrontendEvents(events);
        return ok();
    }
}
