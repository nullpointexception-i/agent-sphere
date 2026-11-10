package com.buukle.agent.superagent.controller;

import com.buukle.agent.common.constant.ApiPrefix;
import com.buukle.agent.common.util.BaseController;
import com.buukle.agent.superagent.dtvo.dto.LocalRuntimeHeartbeatDTO;
import com.buukle.agent.superagent.dtvo.dto.LocalTaskChunkDTO;
import com.buukle.agent.superagent.dtvo.dto.LocalTaskCompleteDTO;
import com.buukle.agent.superagent.dtvo.dto.LocalTaskFailDTO;
import com.buukle.agent.superagent.service.LocalRuntimeAuthInterceptor;
import com.buukle.agent.superagent.service.LocalRuntimeService;
import com.buukle.agent.superagent.service.LocalRuntimeStreamService;
import com.buukle.agent.superagent.service.LocalTaskService;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;

/** Device-token endpoints used by the desktop runtime connector. */
@RestController
@RequestMapping(ApiPrefix.V1 + "/local-runtimes/me")
@RequiredArgsConstructor
public class LocalRuntimeDeviceController extends BaseController {

    private final LocalRuntimeService localRuntimeService;
    private final LocalTaskService localTaskService;
    private final LocalRuntimeStreamService streamService;

    @GetMapping
    public ResponseEntity<?> me(
            @RequestAttribute(LocalRuntimeAuthInterceptor.RUNTIME_ATTR) Long runtimeId) {
        return ok(localRuntimeService.me(runtimeId));
    }

    @PostMapping("/heartbeat")
    public ResponseEntity<?> heartbeat(
            @RequestAttribute(LocalRuntimeAuthInterceptor.RUNTIME_ATTR) Long runtimeId,
            @Valid @RequestBody LocalRuntimeHeartbeatDTO dto) {
        return ok(localRuntimeService.heartbeat(runtimeId, dto));
    }

    @GetMapping("/stream")
    public SseEmitter stream(
            @RequestAttribute(LocalRuntimeAuthInterceptor.RUNTIME_ATTR) Long runtimeId,
            HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-cache, no-transform");
        response.setHeader("X-Accel-Buffering", "no");
        return streamService.register(runtimeId);
    }

    @GetMapping("/tasks")
    public ResponseEntity<?> tasks(
            @RequestAttribute(LocalRuntimeAuthInterceptor.RUNTIME_ATTR) Long runtimeId,
            @RequestParam(required = false) List<String> status) {
        return ok(localTaskService.list(runtimeId, status));
    }

    @PostMapping("/tasks/{id}/ack")
    public ResponseEntity<?> ack(
            @RequestAttribute(LocalRuntimeAuthInterceptor.RUNTIME_ATTR) Long runtimeId,
            @PathVariable Long id) {
        return ok(localTaskService.ack(runtimeId, id));
    }

    @PostMapping("/tasks/{id}/chunk")
    public ResponseEntity<?> chunk(
            @RequestAttribute(LocalRuntimeAuthInterceptor.RUNTIME_ATTR) Long runtimeId,
            @PathVariable Long id,
            @Valid @RequestBody LocalTaskChunkDTO dto) {
        localTaskService.chunk(runtimeId, id, dto);
        return ok(null);
    }

    @PostMapping("/tasks/{id}/complete")
    public ResponseEntity<?> complete(
            @RequestAttribute(LocalRuntimeAuthInterceptor.RUNTIME_ATTR) Long runtimeId,
            @PathVariable Long id,
            @Valid @RequestBody LocalTaskCompleteDTO dto) {
        localTaskService.complete(runtimeId, id, dto);
        return ok(null);
    }

    @PostMapping("/tasks/{id}/fail")
    public ResponseEntity<?> fail(
            @RequestAttribute(LocalRuntimeAuthInterceptor.RUNTIME_ATTR) Long runtimeId,
            @PathVariable Long id,
            @Valid @RequestBody LocalTaskFailDTO dto) {
        localTaskService.fail(runtimeId, id, dto);
        return ok(null);
    }

    @PostMapping("/tasks/{id}/cancel")
    public ResponseEntity<?> cancel(
            @RequestAttribute(LocalRuntimeAuthInterceptor.RUNTIME_ATTR) Long runtimeId,
            @PathVariable Long id) {
        localTaskService.cancel(runtimeId, id);
        return ok(null);
    }
}
