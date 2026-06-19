package com.buukle.agent.capability.cli.controller;

import com.buukle.agent.common.context.WithTenant;
import com.buukle.agent.common.util.BaseController;
import com.buukle.agent.capability.cli.dtvo.dto.CreateCliDTO;
import com.buukle.agent.capability.cli.service.CapabilityCliService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.time.LocalDateTime;

@RestController
@RequestMapping("/api/v1/capability/cli")
@RequiredArgsConstructor
@WithTenant
public class CapabilityCliController extends BaseController {
    private final CapabilityCliService capabilityCliService;

    @PostMapping
    public ResponseEntity<?> create(@Valid @RequestBody CreateCliDTO dto) {
        return created(capabilityCliService.createCli(dto));
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> get(@PathVariable Long id) {
        return ok(capabilityCliService.getCli(id));
    }

    @GetMapping
    public ResponseEntity<?> list(
        @RequestParam(defaultValue = "1") int page,
        @RequestParam(defaultValue = "10") int size,
        @RequestParam(required = false) String keyword,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime startTime,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime endTime) {
        return ok(capabilityCliService.pageClis(page, size, keyword, startTime, endTime));
    }

    @PutMapping("/{id}")
    public ResponseEntity<?> update(@PathVariable Long id, @Valid @RequestBody CreateCliDTO dto) {
        return ok(capabilityCliService.updateCli(id, dto));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(@PathVariable Long id) {
        capabilityCliService.deleteCli(id);
        return ok();
    }

    @DeleteMapping("/batch")
    public ResponseEntity<?> batchDelete(@RequestBody java.util.List<Long> ids) {
        capabilityCliService.batchDeleteCli(ids);
        return ok();
    }
}
