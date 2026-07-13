package com.buukle.agent.model.controller;

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
@RequestMapping("/api/v1/model/api-keys")
@RequiredArgsConstructor
@WithTenant
public class ApiKeyController extends BaseController {
    private final ApiKeyService apiKeyService;

    @RequirePermission("model:apikey:create")
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

    @RequirePermission("model:apikey:update")
    @PutMapping("/{id}")
    public ResponseEntity<?> update(@PathVariable Long id, @Valid @RequestBody CreateApiKeyDTO dto) {
        return ok(apiKeyService.updateApiKey(id, dto));
    }

    @RequirePermission("model:apikey:delete")
    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(@PathVariable Long id) {
        apiKeyService.deleteApiKey(id);
        return ok();
    }
}
