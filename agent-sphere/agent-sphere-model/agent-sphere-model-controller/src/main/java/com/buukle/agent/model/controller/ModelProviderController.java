package com.buukle.agent.model.controller;



import com.buukle.agent.common.constant.ApiPrefix;
import com.buukle.agent.common.constant.PermissionCodes;
import com.buukle.agent.common.annotation.AuditLog;
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
@RequestMapping(ApiPrefix.V1 + "/model/providers")
@RequiredArgsConstructor
@WithTenant
public class ModelProviderController extends BaseController {
    private final ModelProviderService modelProviderService;

    @AuditLog(action = "CREATE", resourceType = "ModelProvider", resourceId = "#result?.body?.id")
    @RequirePermission(PermissionCodes.MODEL_PROVIDER_CREATE)
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

    @AuditLog(action = "UPDATE", resourceType = "ModelProvider", resourceId = "#id")
    @RequirePermission(PermissionCodes.MODEL_PROVIDER_UPDATE)
    @PutMapping("/{id}")
    public ResponseEntity<?> update(@PathVariable Long id, @Valid @RequestBody CreateModelProviderDTO dto) {
        return ok(modelProviderService.updateProvider(id, dto));
    }

    @AuditLog(action = "DELETE", resourceType = "ModelProvider", resourceId = "#id")
    @RequirePermission(PermissionCodes.MODEL_PROVIDER_DELETE)
    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(@PathVariable Long id) {
        modelProviderService.deleteProvider(id);
        return ok();
    }

    @AuditLog(action = "SET_ACTIVE_KEY", resourceType = "ModelProvider", resourceId = "#id")
    @RequirePermission(PermissionCodes.MODEL_APIKEY_SET_ACTIVE)
    @PutMapping("/{id}/active-key")
    public ResponseEntity<?> setActiveKey(@PathVariable Long id, @RequestBody SetActiveKeyDTO dto) {
        modelProviderService.setActiveKey(id, dto.getApiKeyId());
        return ok();
    }
}
