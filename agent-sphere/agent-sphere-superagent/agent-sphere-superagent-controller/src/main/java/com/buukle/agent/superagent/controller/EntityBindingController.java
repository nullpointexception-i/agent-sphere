package com.buukle.agent.superagent.controller;

import com.buukle.agent.common.constant.ApiPrefix;
import com.buukle.agent.common.context.WithTenant;
import com.buukle.agent.common.util.BaseController;
import com.buukle.agent.superagent.dtvo.BindEntityAgentDTO;
import com.buukle.agent.superagent.dtvo.EntityAgentBindingVO;
import com.buukle.agent.superagent.service.EntityBindingService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@WithTenant
@RequestMapping(ApiPrefix.V1 + "/super-agent/entity-bindings")
@RequiredArgsConstructor
public class EntityBindingController extends BaseController {

    private final EntityBindingService entityBindingService;

    @GetMapping
    public ResponseEntity<List<EntityAgentBindingVO>> list(
            @RequestParam String entityType,
            @RequestParam Long entityId) {
        return ok(entityBindingService.list(entityType, entityId));
    }

    @PostMapping
    public ResponseEntity<EntityAgentBindingVO> bind(@Valid @RequestBody BindEntityAgentDTO dto) {
        return created(entityBindingService.bind(dto));
    }

    @DeleteMapping
    public ResponseEntity<?> unbind(
            @RequestParam String entityType,
            @RequestParam Long entityId,
            @RequestParam Long agentId) {
        entityBindingService.unbind(entityType, entityId, agentId);
        return deleted();
    }
}
