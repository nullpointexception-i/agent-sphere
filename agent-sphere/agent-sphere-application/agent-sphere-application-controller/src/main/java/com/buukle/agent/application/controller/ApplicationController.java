package com.buukle.agent.application.controller;

import com.buukle.agent.application.dtvo.ApplicationMemberVO;
import com.buukle.agent.application.dtvo.ApplicationRunVO;
import com.buukle.agent.application.dtvo.ApplicationSceneVO;
import com.buukle.agent.application.dtvo.ApplicationVO;
import com.buukle.agent.application.dtvo.AddApplicationMemberDTO;
import com.buukle.agent.application.dtvo.CreateApplicationDTO;
import com.buukle.agent.application.dtvo.CreateApplicationRunDTO;
import com.buukle.agent.application.dtvo.CreateApplicationSceneDTO;
import com.buukle.agent.application.service.ApplicationRunService;
import com.buukle.agent.application.service.ApplicationService;
import com.buukle.agent.common.constant.ApiPrefix;
import com.buukle.agent.common.context.WithTenant;
import com.buukle.agent.common.util.BaseController;
import com.buukle.agent.common.util.PageResult;
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

import java.util.List;

@RestController
@WithTenant
@RequestMapping(ApiPrefix.V1 + "/application/apps")
@RequiredArgsConstructor
public class ApplicationController extends BaseController {

    private final ApplicationService applicationService;
    private final ApplicationRunService applicationRunService;

    @GetMapping
    public ResponseEntity<PageResult<ApplicationVO>> page(
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        return ok(applicationService.page(keyword, page, pageSize));
    }

    @PostMapping
    public ResponseEntity<Long> create(@Valid @RequestBody CreateApplicationDTO dto) {
        return created(applicationService.create(dto));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApplicationVO> detail(@PathVariable Long id) {
        return ok(applicationService.detail(id));
    }

    @PutMapping("/{id}")
    public ResponseEntity<?> update(@PathVariable Long id, @Valid @RequestBody CreateApplicationDTO dto) {
        applicationService.update(id, dto);
        return ok();
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> disable(@PathVariable Long id) {
        applicationService.disable(id);
        return deleted();
    }

    @GetMapping("/{id}/scenes")
    public ResponseEntity<List<ApplicationSceneVO>> scenes(@PathVariable Long id) {
        return ok(applicationService.scenes(id));
    }

    @PostMapping("/{id}/scenes")
    public ResponseEntity<Long> createScene(
            @PathVariable Long id,
            @Valid @RequestBody CreateApplicationSceneDTO dto) {
        return created(applicationService.createScene(id, dto));
    }

    @PutMapping("/scenes/{sceneId}")
    public ResponseEntity<?> updateScene(
            @PathVariable Long sceneId,
            @Valid @RequestBody CreateApplicationSceneDTO dto) {
        applicationService.updateScene(sceneId, dto);
        return ok();
    }

    @GetMapping("/{id}/members")
    public ResponseEntity<List<ApplicationMemberVO>> members(@PathVariable Long id) {
        return ok(applicationService.members(id));
    }

    @PostMapping("/{id}/members")
    public ResponseEntity<?> addMember(
            @PathVariable Long id,
            @Valid @RequestBody AddApplicationMemberDTO dto) {
        applicationService.addMember(id, dto);
        return ok();
    }

    @DeleteMapping("/{id}/members/{userId}")
    public ResponseEntity<?> removeMember(
            @PathVariable Long id,
            @PathVariable Long userId) {
        applicationService.removeMember(id, userId);
        return deleted();
    }

    @PostMapping("/scenes/{sceneId}/runs")
    public ResponseEntity<ApplicationRunVO> triggerRun(
            @PathVariable Long sceneId,
            @Valid @RequestBody CreateApplicationRunDTO dto) {
        return created(applicationRunService.trigger(sceneId, dto));
    }

    @GetMapping("/runs")
    public ResponseEntity<?> runs(
            @RequestParam(required = false) Long applicationId,
            @RequestParam(required = false) Long sceneId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        return ok(applicationRunService.page(applicationId, sceneId, page, pageSize));
    }

    @GetMapping("/runs/{id}")
    public ResponseEntity<ApplicationRunVO> run(@PathVariable Long id) {
        return ok(applicationRunService.detail(id));
    }

    @PostMapping("/runs/{id}/cancel")
    public ResponseEntity<?> cancelRun(@PathVariable Long id) {
        applicationRunService.cancel(id);
        return ok();
    }

    @PostMapping("/runs/{id}/resume")
    public ResponseEntity<ApplicationRunVO> resumeRun(@PathVariable Long id) {
        return ok(applicationRunService.resume(id));
    }
}
