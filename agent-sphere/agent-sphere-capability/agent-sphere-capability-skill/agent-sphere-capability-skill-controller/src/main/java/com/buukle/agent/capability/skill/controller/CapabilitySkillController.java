package com.buukle.agent.capability.skill.controller;

import com.buukle.agent.common.context.WithTenant;
import com.buukle.agent.common.util.BaseController;
import com.buukle.agent.capability.skill.dtvo.dto.CreateSkillDTO;
import com.buukle.agent.capability.skill.service.CapabilitySkillService;
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

    @PutMapping("/{id}")
    public ResponseEntity<?> update(@PathVariable Long id, @Valid @RequestBody CreateSkillDTO dto) {
        return ok(capabilitySkillService.updateSkill(id, dto));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(@PathVariable Long id) {
        capabilitySkillService.deleteSkill(id);
        return ok();
    }

    @DeleteMapping("/batch")
    public ResponseEntity<?> batchDelete(@RequestBody java.util.List<Long> ids) {
        capabilitySkillService.batchDeleteSkill(ids);
        return ok();
    }
}
