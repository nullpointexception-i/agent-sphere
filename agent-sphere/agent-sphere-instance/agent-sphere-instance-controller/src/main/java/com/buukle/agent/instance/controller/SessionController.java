package com.buukle.agent.instance.controller;

import com.buukle.agent.common.context.WithTenant;
import com.buukle.agent.common.util.BaseController;
import com.buukle.agent.instance.dtvo.dto.CreateSessionDTO;
import com.buukle.agent.instance.dtvo.dto.UpdateSessionDTO;
import com.buukle.agent.instance.dtvo.dto.UpdateSummaryDTO;
import com.buukle.agent.instance.dtvo.vo.SessionVO;
import com.buukle.agent.instance.service.SessionService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/instance/sessions")
@RequiredArgsConstructor
@WithTenant
public class SessionController extends BaseController {
    private final SessionService sessionService;

    @PostMapping
    public ResponseEntity<?> create(@Valid @RequestBody CreateSessionDTO dto) {
        SessionVO vo = sessionService.createSession(dto);
        return created(vo);
    }

    @GetMapping
    public ResponseEntity<?> list(@RequestParam(defaultValue = "0") int offset,
                                  @RequestParam(defaultValue = "20") int limit,
                                  @RequestParam(required = false) String keyword) {
        return ok(sessionService.listSessions(offset, limit, keyword));
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> get(@PathVariable Long id) {
        return ok(sessionService.getSession(id));
    }

    @PutMapping("/{id}")
    public ResponseEntity<?> update(@PathVariable Long id, @Valid @RequestBody UpdateSessionDTO dto) {
        return ok(sessionService.updateSession(id, dto.getTitle()));
    }

    @PutMapping("/{id}/summary")
    public ResponseEntity<?> updateSummary(@PathVariable Long id, @Valid @RequestBody UpdateSummaryDTO dto) {
        sessionService.updateSummaryManually(id, dto.getSummary());
        return ok();
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> close(@PathVariable Long id) {
        sessionService.closeSession(id);
        return deleted();
    }

    @DeleteMapping("/batch")
    public ResponseEntity<?> batchClose(@RequestBody List<Long> ids) {
        sessionService.batchCloseSessions(ids);
        return deleted();
    }
}
