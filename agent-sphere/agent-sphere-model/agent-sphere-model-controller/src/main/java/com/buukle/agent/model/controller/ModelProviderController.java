package com.buukle.agent.model.controller;

import com.buukle.agent.common.context.WithTenant;
import com.buukle.agent.common.annotation.RequirePermission;
import com.buukle.agent.common.util.BaseController;
import com.buukle.agent.model.dtvo.dto.CreateModelProviderDTO;
import com.buukle.agent.model.dtvo.dto.SetActiveKeyDTO;
import com.buukle.agent.model.dtvo.enums.ModelProviderCompany;
import com.buukle.agent.model.service.ModelProviderService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/model/providers")
@RequiredArgsConstructor
@WithTenant
public class ModelProviderController extends BaseController {
    private final ModelProviderService modelProviderService;

    @RequirePermission("model:provider:create")
    @PostMapping
    public ResponseEntity<?> create(@Valid @RequestBody CreateModelProviderDTO dto) {
        return created(modelProviderService.createProvider(dto));
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> get(@PathVariable Long id) {
        return ok(modelProviderService.getProvider(id));
    }

    @GetMapping("/count")
    public ResponseEntity<?> count() {
        return ok(modelProviderService.countProviders());
    }

    @GetMapping
    public ResponseEntity<?> list(
            @RequestParam(required = false) String keyword) {
        return ok(modelProviderService.listProviders(keyword));
    }

    @GetMapping("/companies")
    public ResponseEntity<?> companies() {
        return ok(List.of(ModelProviderCompany.values()));
    }

    @RequirePermission("model:provider:update")
    @PutMapping("/{id}")
    public ResponseEntity<?> update(@PathVariable Long id, @Valid @RequestBody CreateModelProviderDTO dto) {
        return ok(modelProviderService.updateProvider(id, dto));
    }

    @RequirePermission("model:provider:delete")
    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(@PathVariable Long id) {
        modelProviderService.deleteProvider(id);
        return ok();
    }

    @RequirePermission("model:apikey:set-active")
    @PutMapping("/{id}/active-key")
    public ResponseEntity<?> setActiveKey(@PathVariable Long id, @RequestBody SetActiveKeyDTO dto) {
        modelProviderService.setActiveKey(id, dto.getApiKeyId());
        return ok();
    }
}
