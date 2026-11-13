package com.buukle.agent.project.service;

import com.buukle.agent.common.util.PageResult;
import com.buukle.agent.project.dtvo.AddProjectMemberDTO;
import com.buukle.agent.project.dtvo.CreateProjectDTO;
import com.buukle.agent.project.dtvo.CreateRequirementCommentDTO;
import com.buukle.agent.project.dtvo.CreateRequirementDTO;
import com.buukle.agent.project.dtvo.RequirementCommentVO;
import com.buukle.agent.project.dtvo.ProjectMemberVO;
import com.buukle.agent.project.dtvo.ProjectVO;
import com.buukle.agent.project.dtvo.RequirementActivityVO;
import com.buukle.agent.project.dtvo.RequirementVO;
import com.buukle.agent.project.dtvo.UpdateProjectDTO;
import com.buukle.agent.project.dtvo.UpdateRequirementDTO;

import java.util.List;

public interface ProjectService {
    PageResult<ProjectVO> page(String keyword, int page, int pageSize);

    ProjectVO detail(Long id);

    Long create(CreateProjectDTO dto);

    void update(Long id, UpdateProjectDTO dto);

    void archive(Long id);

    void delete(Long id);

    List<ProjectMemberVO> members(Long projectId);

    void addMember(Long projectId, AddProjectMemberDTO dto);

    void removeMember(Long projectId, Long userId);

    PageResult<RequirementVO> requirements(Long projectId, String status, int page, int pageSize);

    PageResult<RequirementVO> requirementPage(Long projectId, String status, String keyword,
                                              int page, int pageSize);

    RequirementVO requirement(Long id);

    Long createRequirement(Long projectId, CreateRequirementDTO dto);

    void updateRequirement(Long id, UpdateRequirementDTO dto);

    void changeRequirementStatus(Long id, String status);

    void deleteRequirement(Long id);

    List<RequirementActivityVO> activities(Long requirementId);

    List<RequirementCommentVO> requirementComments(Long requirementId);

    RequirementCommentVO addRequirementComment(Long requirementId,
                                               CreateRequirementCommentDTO dto);
}
