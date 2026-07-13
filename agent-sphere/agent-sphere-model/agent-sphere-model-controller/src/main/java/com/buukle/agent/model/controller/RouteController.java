package com.buukle.agent.model.controller;

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
@RequestMapping("/api/v1/model/routes")
@RequiredArgsConstructor
@WithTenant
public class RouteController extends BaseController {
    private final RouteService routeService;

    @RequirePermission("model:route:create")
    @PostMapping
    public ResponseEntity<?> create(@Valid @RequestBody CreateRouteDTO dto) {
        return created(routeService.createRoute(dto));
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

    @RequirePermission("model:route:update")
    @PutMapping("/{id}")
    public ResponseEntity<?> update(@PathVariable Long id, @Valid @RequestBody CreateRouteDTO dto) {
        return ok(routeService.updateRoute(id, dto));
    }

    @RequirePermission("model:route:delete")
    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(@PathVariable Long id) {
        routeService.deleteRoute(id);
        return ok();
    }
}
