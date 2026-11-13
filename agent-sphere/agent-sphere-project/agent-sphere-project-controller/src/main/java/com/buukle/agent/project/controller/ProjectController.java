package com.buukle.agent.project.controller;

import com.buukle.agent.common.constant.ApiPrefix;
import com.buukle.agent.common.context.WithTenant;
import com.buukle.agent.common.util.BaseController;
import com.buukle.agent.common.util.PageResult;
import com.buukle.agent.project.dtvo.AddProjectMemberDTO;
import com.buukle.agent.project.dtvo.ChangeRequirementStatusDTO;
import com.buukle.agent.project.dtvo.CreateProjectDTO;
import com.buukle.agent.project.dtvo.CreateRequirementCommentDTO;
import com.buukle.agent.project.dtvo.CreateRequirementDTO;
import com.buukle.agent.project.dtvo.ProjectMemberVO;
import com.buukle.agent.project.dtvo.ProjectVO;
import com.buukle.agent.project.dtvo.RequirementActivityVO;
import com.buukle.agent.project.dtvo.RequirementCommentVO;
import com.buukle.agent.project.dtvo.RequirementVO;
import com.buukle.agent.project.dtvo.UpdateProjectDTO;
import com.buukle.agent.project.dtvo.UpdateRequirementDTO;
import com.buukle.agent.project.service.ProjectService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
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
public class ProjectController extends BaseController {

    private final ProjectService projectService;

    @GetMapping("/projects")
    public ResponseEntity<PageResult<ProjectVO>> page(
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        return ok(projectService.page(keyword, page, pageSize));
    }

    @PostMapping("/projects")
    public ResponseEntity<Long> create(@Valid @RequestBody CreateProjectDTO dto) {
        return created(projectService.create(dto));
    }

    @GetMapping("/projects/{id}")
    public ResponseEntity<ProjectVO> detail(@PathVariable Long id) {
        return ok(projectService.detail(id));
    }

    @PutMapping("/projects/{id}")
    public ResponseEntity<?> update(@PathVariable Long id, @Valid @RequestBody UpdateProjectDTO dto) {
        projectService.update(id, dto);
        return ok();
    }

    @PostMapping("/projects/{id}/archive")
    public ResponseEntity<?> archive(@PathVariable Long id) {
        projectService.archive(id);
        return ok();
    }

    @DeleteMapping("/projects/{id}")
    public ResponseEntity<?> delete(@PathVariable Long id) {
        projectService.delete(id);
        return deleted();
    }

    @GetMapping("/projects/{id}/members")
    public ResponseEntity<List<ProjectMemberVO>> members(@PathVariable Long id) {
        return ok(projectService.members(id));
    }

    @PostMapping("/projects/{id}/members")
    public ResponseEntity<?> addMember(@PathVariable Long id, @Valid @RequestBody AddProjectMemberDTO dto) {
        projectService.addMember(id, dto);
        return ok();
    }

    @DeleteMapping("/projects/{id}/members/{userId}")
    public ResponseEntity<?> removeMember(@PathVariable Long id, @PathVariable Long userId) {
        projectService.removeMember(id, userId);
        return deleted();
    }

    @GetMapping("/projects/{id}/requirements")
    public ResponseEntity<PageResult<RequirementVO>> requirements(
            @PathVariable Long id,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        return ok(projectService.requirements(id, status, page, pageSize));
    }

    @PostMapping("/projects/{id}/requirements")
    public ResponseEntity<Long> createRequirement(
            @PathVariable Long id,
            @Valid @RequestBody CreateRequirementDTO dto) {
        return created(projectService.createRequirement(id, dto));
    }

    @GetMapping("/requirements")
    public ResponseEntity<PageResult<RequirementVO>> requirementPage(
            @RequestParam(required = false) Long projectId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        return ok(projectService.requirementPage(projectId, status, keyword, page, pageSize));
    }

    @GetMapping("/requirements/{id}")
    public ResponseEntity<RequirementVO> requirement(@PathVariable Long id) {
        return ok(projectService.requirement(id));
    }

    @PutMapping("/requirements/{id}")
    public ResponseEntity<?> updateRequirement(
            @PathVariable Long id,
            @Valid @RequestBody UpdateRequirementDTO dto) {
        projectService.updateRequirement(id, dto);
        return ok();
    }

    @PatchMapping("/requirements/{id}/status")
    public ResponseEntity<?> changeStatus(
            @PathVariable Long id,
            @Valid @RequestBody ChangeRequirementStatusDTO dto) {
        projectService.changeRequirementStatus(id, dto.getStatus());
        return ok();
    }

    @GetMapping("/requirements/{id}/activities")
    public ResponseEntity<List<RequirementActivityVO>> activities(@PathVariable Long id) {
        return ok(projectService.activities(id));
    }

    @GetMapping("/requirements/{id}/comments")
    public ResponseEntity<List<RequirementCommentVO>> comments(@PathVariable Long id) {
        return ok(projectService.requirementComments(id));
    }

    @PostMapping("/requirements/{id}/comments")
    public ResponseEntity<RequirementCommentVO> addComment(
            @PathVariable Long id,
            @Valid @RequestBody CreateRequirementCommentDTO dto) {
        return created(projectService.addRequirementComment(id, dto));
    }
}
