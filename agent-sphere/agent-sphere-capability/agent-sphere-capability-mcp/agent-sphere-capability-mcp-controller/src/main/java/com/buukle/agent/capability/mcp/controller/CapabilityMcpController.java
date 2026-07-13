package com.buukle.agent.capability.mcp.controller;

import com.buukle.agent.capability.mcp.dtvo.dto.CreateMcpDTO;
import com.buukle.agent.capability.mcp.service.CapabilityMcpService;
import com.buukle.agent.common.context.WithTenant;
import com.buukle.agent.common.annotation.RequirePermission;
import com.buukle.agent.common.util.BaseController;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;

@RestController
@RequestMapping("/api/v1/capability/mcp")
@RequiredArgsConstructor
@WithTenant
public class CapabilityMcpController extends BaseController {
    private final CapabilityMcpService capabilityMcpService;

    @RequirePermission("capability:mcp:create")
    @PostMapping
    public ResponseEntity<?> create(@Valid @RequestBody CreateMcpDTO dto) {
        return created(capabilityMcpService.createMcp(dto));
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> get(@PathVariable Long id) {
        return ok(capabilityMcpService.getMcp(id));
    }

    @GetMapping
    public ResponseEntity<?> list(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime startTime,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime endTime) {
        return ok(capabilityMcpService.pageMcps(page, size, keyword, startTime, endTime));
    }

    @RequirePermission("capability:mcp:update")
    @PutMapping("/{id}")
    public ResponseEntity<?> update(@PathVariable Long id, @Valid @RequestBody CreateMcpDTO dto) {
        return ok(capabilityMcpService.updateMcp(id, dto));
    }

    @RequirePermission("capability:mcp:delete")
    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(@PathVariable Long id) {
        capabilityMcpService.deleteMcp(id);
        return ok();
    }

    @RequirePermission("capability:mcp:delete")
    @DeleteMapping("/batch")
    public ResponseEntity<?> batchDelete(@RequestBody java.util.List<Long> ids) {
        capabilityMcpService.batchDeleteMcp(ids);
        return ok();
    }
}
