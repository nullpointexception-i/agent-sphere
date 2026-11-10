package com.buukle.agent.instance.controller;


import com.buukle.agent.common.constant.ApiPrefix;
import com.buukle.agent.common.constant.PermissionCodes;
import com.buukle.agent.common.constant.SessionListScope;
import com.buukle.agent.common.context.AuthContext;
import com.buukle.agent.common.context.WithTenant;
import com.buukle.agent.common.util.BaseController;
import com.buukle.agent.instance.dtvo.dto.CreateSessionDTO;
import com.buukle.agent.instance.dtvo.dto.UpdateSessionDTO;
import com.buukle.agent.instance.dtvo.dto.UpdateSessionRunModeDTO;
import com.buukle.agent.instance.dtvo.dto.UpdateSummaryDTO;
import com.buukle.agent.instance.dtvo.vo.SessionVO;
import com.buukle.agent.instance.service.SessionService;
import com.buukle.agent.instance.service.support.SessionAccessGuard;
import com.buukle.agent.instance.spi.RunSpi;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping(ApiPrefix.V1 + "/instance/sessions")
@RequiredArgsConstructor
@WithTenant
public class SessionController extends BaseController {
    private final SessionService sessionService;
    private final RunSpi runSpi;
    private final SessionAccessGuard sessionAccessGuard;

    @PostMapping
    public ResponseEntity<?> create(@Valid @RequestBody CreateSessionDTO dto) {
        SessionVO vo = sessionService.createSession(dto);
        return created(vo);
    }

    @GetMapping
    public ResponseEntity<?> list(@RequestParam(defaultValue = "0") int offset,
                                  @RequestParam(defaultValue = "20") int limit,
                                  @RequestParam(required = false) String keyword,
                                  @RequestParam(required = false) String scope,
                                  @RequestParam(required = false) String role) {
        if (SessionListScope.TENANT.equalsIgnoreCase(scope)
                && !AuthContext.isSuperAdmin()
                && !AuthContext.hasPermission(PermissionCodes.INSTANCE_TENANT)) {
            throw new com.buukle.agent.common.exception.BizException(
                    com.buukle.agent.common.error.CommonErrorCode.FORBIDDEN,
                    "Tenant conversations require tenant administrator permission");
        }
        return ok(sessionService.listSessions(offset, limit, keyword, scope, role));
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> get(@PathVariable Long id) {
        return ok(sessionAccessGuard.requireReadable(id));
    }

    @PutMapping("/{id}")
    public ResponseEntity<?> update(@PathVariable Long id, @Valid @RequestBody UpdateSessionDTO dto) {
        sessionAccessGuard.requireWritable(id);
        return ok(sessionService.updateSession(id, dto.getTitle()));
    }

    @PutMapping("/{id}/run-mode")
    public ResponseEntity<?> updateRunMode(@PathVariable Long id,
                                           @Valid @RequestBody UpdateSessionRunModeDTO dto) {
        sessionAccessGuard.requireWritable(id);
        return ok(sessionService.updateRunMode(id, dto.getRunMode()));
    }

    @PutMapping("/{id}/summary")
    public ResponseEntity<?> updateSummary(@PathVariable Long id, @Valid @RequestBody UpdateSummaryDTO dto) {
        sessionAccessGuard.requireWritable(id);
        sessionService.updateSummaryManually(id, dto.getSummary());
        return ok();
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> close(@PathVariable Long id) {
        sessionAccessGuard.requireWritable(id);
        sessionService.closeSession(id);
        return deleted();
    }

    @DeleteMapping("/batch")
    public ResponseEntity<?> batchClose(@RequestBody List<Long> ids) {
        for (Long id : ids) {
            sessionAccessGuard.requireWritable(id);
        }
        sessionService.batchCloseSessions(ids);
        return deleted();
    }

    @GetMapping("/{id}/history/messages")
    public ResponseEntity<?> messageHistory(@PathVariable Long id,
                                            @RequestParam String direction,
                                            @RequestParam(required = false) Long runId) {
        sessionAccessGuard.requireReadable(id);
        return ok(runSpi.getMessageHistory(id, direction, runId));
    }
}
