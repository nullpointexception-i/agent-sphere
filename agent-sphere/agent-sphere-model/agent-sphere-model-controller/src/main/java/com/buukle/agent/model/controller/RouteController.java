package com.buukle.agent.model.controller;



import com.buukle.agent.common.constant.ApiPrefix;
import com.buukle.agent.common.constant.PermissionCodes;
import com.buukle.agent.common.annotation.AuditLog;
import com.buukle.agent.common.context.WithTenant;
import com.buukle.agent.common.annotation.RequirePermission;
import com.buukle.agent.common.util.BaseController;
import com.buukle.agent.model.dtvo.dto.CreateRouteDTO;
import com.buukle.agent.model.service.RouteService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping(ApiPrefix.V1 + "/model/routes")
@RequiredArgsConstructor
@WithTenant
public class RouteController extends BaseController {
    private final RouteService routeService;

    @AuditLog(action = "CREATE", resourceType = "ModelRoute", resourceId = "#result?.body?.id")
    @RequirePermission(PermissionCodes.MODEL_ROUTE_CREATE)
    @PostMapping
    public ResponseEntity<?> create(@Valid @RequestBody CreateRouteDTO dto) {
        return created(routeService.createRoute(dto));
    }

    @GetMapping("/default")
    public ResponseEntity<?> getDefault() {
        return ok(routeService.getDefaultRoute());
    }

    /** System routes (one per tenant) for the audit view. */
    @GetMapping("/system")
    public ResponseEntity<?> listSystem() {
        return ok(routeService.listSystemRoutes());
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> get(@PathVariable Long id) {
        return ok(routeService.getRoute(id));
    }

    @GetMapping
    public ResponseEntity<?> listByProvider(@RequestParam(required = false) Long providerId,
                                            @RequestParam(required = false) String keyword) {
        if (providerId == null) {
            return ok(routeService.listAllRoutes(keyword));
        }
        return ok(routeService.listRoutesByProvider(providerId, keyword));
    }

    @AuditLog(action = "UPDATE", resourceType = "ModelRoute", resourceId = "#id")
    @RequirePermission(PermissionCodes.MODEL_ROUTE_UPDATE)
    @PutMapping("/{id}")
    public ResponseEntity<?> update(@PathVariable Long id, @Valid @RequestBody CreateRouteDTO dto) {
        return ok(routeService.updateRoute(id, dto));
    }

    @AuditLog(action = "SET_DEFAULT", resourceType = "ModelRoute", resourceId = "#id")
    @RequirePermission(PermissionCodes.MODEL_ROUTE_UPDATE)
    @PutMapping("/{id}/default")
    public ResponseEntity<?> setDefault(@PathVariable Long id) {
        return ok(routeService.setDefault(id));
    }

    @AuditLog(action = "DELETE", resourceType = "ModelRoute", resourceId = "#id")
    @RequirePermission(PermissionCodes.MODEL_ROUTE_DELETE)
    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(@PathVariable Long id) {
        routeService.deleteRoute(id);
        return ok();
    }
}
