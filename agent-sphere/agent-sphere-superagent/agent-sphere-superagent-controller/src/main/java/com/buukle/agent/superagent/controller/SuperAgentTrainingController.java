package com.buukle.agent.superagent.controller;

import com.buukle.agent.common.annotation.RequirePermission;
import com.buukle.agent.common.constant.ApiPrefix;
import com.buukle.agent.common.constant.PermissionCodes;
import com.buukle.agent.common.context.WithTenant;
import com.buukle.agent.common.util.BaseController;
import com.buukle.agent.superagent.dtvo.dto.TrainingMessageDTO;
import com.buukle.agent.superagent.service.SuperAgentTrainingService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(ApiPrefix.V1 + "/super-agents/{id}/training-sessions")
@WithTenant
@RequiredArgsConstructor
public class SuperAgentTrainingController extends BaseController {

    private final SuperAgentTrainingService trainingService;

    @RequirePermission(PermissionCodes.SUPER_AGENT_UPDATE)
    @PostMapping
    public ResponseEntity<?> ensure(@PathVariable Long id) {
        return created(trainingService.ensureSession(id));
    }

    @RequirePermission(PermissionCodes.SUPER_AGENT_UPDATE)
    @PostMapping("/{sessionId}/messages")
    public ResponseEntity<?> send(@PathVariable Long id,
                                  @PathVariable Long sessionId,
                                  @Valid @RequestBody TrainingMessageDTO dto) {
        return ok(trainingService.send(id, sessionId, dto));
    }

    @RequirePermission(PermissionCodes.SUPER_AGENT_UPDATE)
    @PostMapping("/{sessionId}/reset")
    public ResponseEntity<?> reset(@PathVariable Long id,
                                   @PathVariable Long sessionId) {
        trainingService.reset(id, sessionId);
        return ok();
    }
}
