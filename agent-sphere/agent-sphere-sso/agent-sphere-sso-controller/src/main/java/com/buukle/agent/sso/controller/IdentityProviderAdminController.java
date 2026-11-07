package com.buukle.agent.sso.controller;


import com.buukle.agent.common.constant.ApiPrefix;
import com.buukle.agent.common.constant.PermissionCodes;
import com.buukle.agent.common.annotation.AuditLog;
import com.buukle.agent.common.annotation.RequirePermission;
import com.buukle.agent.common.util.BaseController;
import com.buukle.agent.sso.dtvo.dto.CreateIdentityProviderDTO;
import com.buukle.agent.sso.dtvo.dto.SetProviderEnabledDTO;
import com.buukle.agent.sso.dtvo.dto.UpdateIdentityProviderDTO;
import com.buukle.agent.sso.service.IdentityProviderService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(ApiPrefix.V1 + "/admin/identity-providers")
@RequiredArgsConstructor
public class IdentityProviderAdminController extends BaseController {

    private final IdentityProviderService identityProviderService;

    @AuditLog(action = "CREATE", resourceType = "IdentityProvider", resourceId = "#result?.body?.id")
    @RequirePermission(PermissionCodes.ADMIN_IDENTITY_PROVIDER_CREATE)
    @PostMapping
    public ResponseEntity<?> create(@Valid @RequestBody CreateIdentityProviderDTO dto) {
        return created(identityProviderService.createProvider(dto));
    }

    @RequirePermission(PermissionCodes.ADMIN_IDENTITY_PROVIDER_READ)
    @GetMapping("/{id}")
    public ResponseEntity<?> get(@PathVariable Long id) {
        return ok(identityProviderService.getProvider(id));
    }

    @RequirePermission(PermissionCodes.ADMIN_IDENTITY_PROVIDER_READ)
    @GetMapping
    public ResponseEntity<?> list(@RequestParam(required = false) String keyword) {
        return ok(identityProviderService.listProviders(keyword));
    }

    @AuditLog(action = "UPDATE", resourceType = "IdentityProvider", resourceId = "#id")
    @RequirePermission(PermissionCodes.ADMIN_IDENTITY_PROVIDER_UPDATE)
    @PutMapping("/{id}")
    public ResponseEntity<?> update(@PathVariable Long id, @Valid @RequestBody UpdateIdentityProviderDTO dto) {
        return ok(identityProviderService.updateProvider(id, dto));
    }

    @AuditLog(action = "DELETE", resourceType = "IdentityProvider", resourceId = "#id")
    @RequirePermission(PermissionCodes.ADMIN_IDENTITY_PROVIDER_DELETE)
    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(@PathVariable Long id) {
        identityProviderService.deleteProvider(id);
        return ok();
    }

    @AuditLog(action = "SET_ENABLED", resourceType = "IdentityProvider", resourceId = "#id")
    @RequirePermission(PermissionCodes.ADMIN_IDENTITY_PROVIDER_UPDATE)
    @PutMapping("/{id}/enabled")
    public ResponseEntity<?> setEnabled(@PathVariable Long id, @Valid @RequestBody SetProviderEnabledDTO dto) {
        identityProviderService.setEnabled(id, dto.getEnabled());
        return ok();
    }

    @RequirePermission(PermissionCodes.ADMIN_IDENTITY_PROVIDER_READ)
    @GetMapping("/resource-template-default")
    public ResponseEntity<?> getDefaultResourceTemplate() {
        return ok(identityProviderService.getDefaultResourceTemplate());
    }

    @AuditLog(action = "TEST", resourceType = "IdentityProvider", resourceId = "#id")
    @RequirePermission(PermissionCodes.ADMIN_IDENTITY_PROVIDER_READ)
    @PostMapping("/{id}/test")
    public ResponseEntity<?> testConnection(@PathVariable Long id) {
        identityProviderService.testConnection(id);
        return ok();
    }
}