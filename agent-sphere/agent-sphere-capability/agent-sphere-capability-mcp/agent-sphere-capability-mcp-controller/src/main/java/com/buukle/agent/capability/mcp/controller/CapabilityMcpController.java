package com.buukle.agent.capability.mcp.controller;



import com.buukle.agent.common.constant.ApiPrefix;
import com.buukle.agent.common.constant.PermissionCodes;
import com.buukle.agent.capability.mcp.dtvo.dto.CreateMcpDTO;
import com.buukle.agent.capability.mcp.service.CapabilityMcpService;
import com.buukle.agent.capability.mcp.service.McpHubService;
import com.buukle.agent.common.annotation.AuditLog;
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
@RequestMapping(ApiPrefix.V1 + "/capability/mcp")
@RequiredArgsConstructor
@WithTenant
public class CapabilityMcpController extends BaseController {
    private final CapabilityMcpService capabilityMcpService;
    private final McpHubService mcpHubService;

    @AuditLog(action = "CREATE", resourceType = "Capability", resourceId = "#result?.body?.id")
    @RequirePermission(PermissionCodes.CAPABILITY_MCP_CREATE)
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

    @AuditLog(action = "UPDATE", resourceType = "Capability", resourceId = "#id")
    @RequirePermission(PermissionCodes.CAPABILITY_MCP_UPDATE)
    @PutMapping("/{id}")
    public ResponseEntity<?> update(@PathVariable Long id, @Valid @RequestBody CreateMcpDTO dto) {
        return ok(capabilityMcpService.updateMcp(id, dto));
    }

    @AuditLog(action = "DELETE", resourceType = "Capability", resourceId = "#id")
    @RequirePermission(PermissionCodes.CAPABILITY_MCP_DELETE)
    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(@PathVariable Long id) {
        capabilityMcpService.deleteMcp(id);
        return ok();
    }

    @AuditLog(action = "BATCH_DELETE", resourceType = "Capability", resourceId = "#ids?.toString()")
    @RequirePermission(PermissionCodes.CAPABILITY_MCP_DELETE)
    @DeleteMapping("/batch")
    public ResponseEntity<?> batchDelete(@RequestBody java.util.List<Long> ids) {
        capabilityMcpService.batchDeleteMcp(ids);
        return ok();
    }

    @RequirePermission(PermissionCodes.CAPABILITY_MCP_TEST)
    @PostMapping("/{id}/test")
    public ResponseEntity<?> testConnection(@PathVariable Long id) {
        return ok(capabilityMcpService.testConnection(id));
    }

    @RequirePermission(PermissionCodes.CAPABILITY_MCP_TEST)
    @GetMapping("/{id}/tools")
    public ResponseEntity<?> listTools(@PathVariable Long id) {
        return ok(capabilityMcpService.listMcpTools(id));
    }

    @RequirePermission(PermissionCodes.CAPABILITY_MCP_TEST)
    @PostMapping("/{id}/tools/{toolName}/call")
    public ResponseEntity<?> callTool(
            @PathVariable Long id,
            @PathVariable String toolName,
            @RequestBody(required = false) java.util.Map<String, Object> body) {
        String argumentsJson = body != null
                ? com.buukle.agent.util.json.JsonUtils.toJson(body)
                : null;
        return ok(capabilityMcpService.callToolByMcpId(id, toolName, argumentsJson));
    }

    // ==================== Tenant MCP Hub ====================

    @GetMapping("/hub")
    public ResponseEntity<?> hub(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) String keyword) {
        return ok(mcpHubService.pageHubMcps(page, size, keyword));
    }

    @GetMapping("/hub/followed")
    public ResponseEntity<?> followed() {
        return ok(mcpHubService.listFollowed());
    }

    @GetMapping("/hub/{hubId}")
    public ResponseEntity<?> hubDetail(@PathVariable Long hubId) {
        return ok(mcpHubService.getHub(hubId));
    }

    @AuditLog(action = "PUBLISH", resourceType = "Capability", resourceId = "#id")
    @RequirePermission(PermissionCodes.CAPABILITY_MCP_CREATE)
    @PostMapping("/{id}/publish")
    public ResponseEntity<?> publish(@PathVariable Long id) {
        return ok(mcpHubService.publish(id));
    }

    @RequirePermission(PermissionCodes.CAPABILITY_MCP_TEST)
    @PostMapping("/hub/{hubId}/follow")
    public ResponseEntity<?> follow(@PathVariable Long hubId) {
        mcpHubService.follow(hubId);
        return ok(true);
    }

    @RequirePermission(PermissionCodes.CAPABILITY_MCP_TEST)
    @DeleteMapping("/hub/{hubId}/follow")
    public ResponseEntity<?> unfollow(@PathVariable Long hubId) {
        mcpHubService.unfollow(hubId);
        return ok(true);
    }

    @AuditLog(action = "INSTALL", resourceType = "Capability", resourceId = "#hubId")
    @RequirePermission(PermissionCodes.CAPABILITY_MCP_CREATE)
    @PostMapping("/hub/{hubId}/install")
    public ResponseEntity<?> installFromHub(
            @PathVariable Long hubId,
            @RequestBody(required = false) java.util.Map<String, Object> body) {
        boolean autoUpdate = body != null && body.get("autoUpdate") != null
                && Boolean.parseBoolean(String.valueOf(body.get("autoUpdate")));
        return created(mcpHubService.install(hubId, autoUpdate));
    }
}
