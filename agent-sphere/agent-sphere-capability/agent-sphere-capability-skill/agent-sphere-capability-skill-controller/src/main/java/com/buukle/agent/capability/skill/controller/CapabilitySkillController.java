package com.buukle.agent.capability.skill.controller;

import com.buukle.agent.capability.skill.dtvo.dto.CreateSkillDTO;
import com.buukle.agent.capability.skill.service.CapabilitySkillService;
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
@RequestMapping("/api/v1/capability/skill")
@RequiredArgsConstructor
@WithTenant
public class CapabilitySkillController extends BaseController {
    private final CapabilitySkillService capabilitySkillService;

    @AuditLog(action = "CREATE", resourceType = "Capability", resourceId = "#result?.body?.id")
    @RequirePermission("capability:skill:create")
    @PostMapping
    public ResponseEntity<?> create(@Valid @RequestBody CreateSkillDTO dto) {
        return created(capabilitySkillService.createSkill(dto));
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> get(@PathVariable Long id) {
        return ok(capabilitySkillService.getSkill(id));
    }

    @GetMapping
    public ResponseEntity<?> list(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime startTime,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime endTime) {
        return ok(capabilitySkillService.pageSkills(page, size, keyword, startTime, endTime));
    }

    @AuditLog(action = "UPDATE", resourceType = "Capability", resourceId = "#id")
    @RequirePermission("capability:skill:update")
    @PutMapping("/{id}")
    public ResponseEntity<?> update(@PathVariable Long id, @Valid @RequestBody CreateSkillDTO dto) {
        return ok(capabilitySkillService.updateSkill(id, dto));
    }

    @AuditLog(action = "DELETE", resourceType = "Capability", resourceId = "#id")
    @RequirePermission("capability:skill:delete")
    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(@PathVariable Long id) {
        capabilitySkillService.deleteSkill(id);
        return ok();
    }

    @AuditLog(action = "BATCH_DELETE", resourceType = "Capability", resourceId = "#ids?.toString()")
    @RequirePermission("capability:skill:delete")
    @DeleteMapping("/batch")
    public ResponseEntity<?> batchDelete(@RequestBody java.util.List<Long> ids) {
        capabilitySkillService.batchDeleteSkill(ids);
        return ok();
    }
}
