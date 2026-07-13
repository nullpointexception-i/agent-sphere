package com.buukle.agent.instance.controller;

import com.buukle.agent.common.context.WithTenant;
import com.buukle.agent.common.annotation.RequirePermission;
import com.buukle.agent.common.util.BaseController;
import com.buukle.agent.instance.dtvo.dto.CreateInstanceDTO;
import com.buukle.agent.instance.dtvo.dto.SetModelRouteDTO;
import com.buukle.agent.instance.service.InstanceService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;

@RestController
@RequestMapping("/api/v1/instance/instances")
@RequiredArgsConstructor
@WithTenant
public class InstanceController extends BaseController {
    private final InstanceService instanceService;

    @RequirePermission("instance:create")
    @PostMapping
    public ResponseEntity<?> create(@Valid @RequestBody CreateInstanceDTO dto) {
        return created(instanceService.createInstance(dto));
    }

    @GetMapping("/all")
    public ResponseEntity<?> listAll() {
        return ok(instanceService.listInstances(null, null, null));
    }

    @GetMapping("/latest/{n}")
    public ResponseEntity<?> listLatest(@PathVariable int n) {
        return ok(instanceService.listLatestNInstances(n));
    }

    @GetMapping
    public ResponseEntity<?> list(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime startTime,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime endTime) {
        return ok(instanceService.pageInstances(page, size, keyword, startTime, endTime));
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> get(@PathVariable Long id) {
        return ok(instanceService.getInstance(id));
    }

    @RequirePermission("instance:update")
    @PutMapping("/{id}")
    public ResponseEntity<?> update(@PathVariable Long id, @Valid @RequestBody CreateInstanceDTO dto) {
        return ok(instanceService.updateInstance(id, dto));
    }

    @RequirePermission("instance:bind-model")
    @PutMapping("/{id}/model-route")
    public ResponseEntity<?> setModelRoute(@PathVariable Long id, @RequestBody SetModelRouteDTO dto) {
        return ok(instanceService.setModelRoute(id, dto.getModelRouteId()));
    }

    @RequirePermission("instance:delete")
    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(@PathVariable Long id) {
        instanceService.deleteInstance(id);
        return ok();
    }

    @RequirePermission("instance:delete")
    @DeleteMapping("/batch")
    public ResponseEntity<?> batchDelete(@RequestBody List<Long> ids) {
        instanceService.batchDeleteInstances(ids);
        return ok();
    }
}
