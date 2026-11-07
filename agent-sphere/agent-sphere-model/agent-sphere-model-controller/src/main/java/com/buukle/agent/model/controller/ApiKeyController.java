package com.buukle.agent.model.controller;



import com.buukle.agent.common.constant.ApiPrefix;
import com.buukle.agent.common.constant.PermissionCodes;
import com.buukle.agent.common.annotation.AuditLog;
import com.buukle.agent.common.context.WithTenant;
import com.buukle.agent.common.annotation.RequirePermission;
import com.buukle.agent.common.util.BaseController;
import com.buukle.agent.model.dtvo.dto.CreateApiKeyDTO;
import com.buukle.agent.model.service.ApiKeyService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping(ApiPrefix.V1 + "/model/api-keys")
@RequiredArgsConstructor
@WithTenant
public class ApiKeyController extends BaseController {
    private final ApiKeyService apiKeyService;

    @AuditLog(action = "CREATE", resourceType = "ApiKey", resourceId = "#result?.body?.id")
    @RequirePermission(PermissionCodes.MODEL_APIKEY_CREATE)
    @PostMapping
    public ResponseEntity<?> create(@Valid @RequestBody CreateApiKeyDTO dto) {
        return created(apiKeyService.createApiKey(dto));
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> get(@PathVariable Long id) {
        return ok(apiKeyService.getApiKey(id));
    }

    @GetMapping
    public ResponseEntity<?> listByProvider(@RequestParam Long providerId,
                                            @RequestParam(required = false) String keyword) {
        return ok(apiKeyService.listApiKeysByProvider(providerId, keyword));
    }

    @AuditLog(action = "UPDATE", resourceType = "ApiKey", resourceId = "#id")
    @RequirePermission(PermissionCodes.MODEL_APIKEY_UPDATE)
    @PutMapping("/{id}")
    public ResponseEntity<?> update(@PathVariable Long id, @Valid @RequestBody CreateApiKeyDTO dto) {
        return ok(apiKeyService.updateApiKey(id, dto));
    }

    @AuditLog(action = "DELETE", resourceType = "ApiKey", resourceId = "#id")
    @RequirePermission(PermissionCodes.MODEL_APIKEY_DELETE)
    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(@PathVariable Long id) {
        apiKeyService.deleteApiKey(id);
        return ok();
    }
}
