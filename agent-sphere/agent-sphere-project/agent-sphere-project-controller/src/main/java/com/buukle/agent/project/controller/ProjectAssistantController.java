package com.buukle.agent.project.controller;

import com.buukle.agent.common.constant.ApiPrefix;
import com.buukle.agent.common.context.WithTenant;
import com.buukle.agent.common.util.BaseController;
import com.buukle.agent.project.dtvo.ProjectAssistantAnswerDTO;
import com.buukle.agent.project.dtvo.ProjectAssistantAvatarVO;
import com.buukle.agent.project.dtvo.ProjectAssistantInboxVO;
import com.buukle.agent.project.dtvo.ProjectAssistantSessionVO;
import com.buukle.agent.project.dtvo.ProjectAssistantSettingsDTO;
import com.buukle.agent.project.dtvo.ProjectAssistantSettingsVO;
import com.buukle.agent.project.dtvo.ProjectContextEventVO;
import com.buukle.agent.project.dtvo.ProjectInsightVO;
import com.buukle.agent.project.service.ProjectAssistantService;
import com.buukle.agent.project.service.ProjectInsightService;
import com.buukle.agent.project.spi.ProjectAssistantRunResult;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
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
@RequestMapping(ApiPrefix.V1 + "/project")
@RequiredArgsConstructor
public class ProjectAssistantController extends BaseController {

    private final ProjectAssistantService assistantService;
    private final ProjectInsightService insightService;

    @PostMapping("/projects/{projectId}/assistant/session")
    public ResponseEntity<ProjectAssistantSessionVO> session(@PathVariable Long projectId) {
        return ok(assistantService.ensureSession(projectId));
    }

    @GetMapping("/assistant/inbox")
    public ResponseEntity<List<ProjectAssistantInboxVO>> inbox() {
        return ok(assistantService.inbox());
    }

    @PostMapping("/assistant/clarifications/{clarificationId}/answer")
    public ResponseEntity<?> answer(@PathVariable String clarificationId,
                                    @Valid @RequestBody ProjectAssistantAnswerDTO dto) {
        assistantService.answer(clarificationId, dto.getResponse());
        return ok();
    }

    @GetMapping("/projects/{projectId}/assistant/settings")
    public ResponseEntity<ProjectAssistantSettingsVO> settings(@PathVariable Long projectId) {
        return ok(assistantService.settings(projectId));
    }

    @PutMapping("/projects/{projectId}/assistant/settings")
    public ResponseEntity<?> updateSettings(@PathVariable Long projectId,
                                            @RequestBody ProjectAssistantSettingsDTO dto) {
        assistantService.updateSettings(projectId, dto.getAutoAnalyze());
        return ok();
    }

    @GetMapping("/projects/{projectId}/assistant/avatars")
    public ResponseEntity<List<ProjectAssistantAvatarVO>> avatars(@PathVariable Long projectId) {
        return ok(assistantService.avatars(projectId));
    }

    @PostMapping("/projects/{projectId}/assistant/analyze")
    public ResponseEntity<ProjectAssistantRunResult> analyze(
            @PathVariable Long projectId,
            @RequestParam(required = false) Long requirementId) {
        return ok(assistantService.triggerAnalysis(projectId, requirementId));
    }

    @GetMapping("/projects/{projectId}/insights")
    public ResponseEntity<List<ProjectInsightVO>> insights(
            @PathVariable Long projectId,
            @RequestParam(required = false) Long requirementId,
            @RequestParam(defaultValue = "20") int limit) {
        assistantService.settings(projectId);
        return ok(insightService.list(projectId, requirementId, limit));
    }

    @GetMapping("/projects/{projectId}/assistant/context")
    public ResponseEntity<List<ProjectContextEventVO>> context(
            @PathVariable Long projectId,
            @RequestParam(defaultValue = "30") int limit) {
        return ok(assistantService.context(projectId, limit));
    }
}
