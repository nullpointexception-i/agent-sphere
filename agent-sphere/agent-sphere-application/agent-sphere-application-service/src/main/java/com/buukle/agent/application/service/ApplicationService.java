package com.buukle.agent.application.service;

import com.buukle.agent.application.dtvo.ApplicationMemberVO;
import com.buukle.agent.application.dtvo.ApplicationSceneVO;
import com.buukle.agent.application.dtvo.ApplicationVO;
import com.buukle.agent.application.dtvo.CreateApplicationDTO;
import com.buukle.agent.application.dtvo.CreateApplicationSceneDTO;
import com.buukle.agent.application.dtvo.AddApplicationMemberDTO;
import com.buukle.agent.common.util.PageResult;

import java.util.List;

public interface ApplicationService {
    PageResult<ApplicationVO> page(String keyword, int page, int pageSize);

    ApplicationVO detail(Long id);

    ApplicationVO detailSystem(Long id);

    Long create(CreateApplicationDTO dto);

    void update(Long id, CreateApplicationDTO dto);

    void disable(Long id);

    List<ApplicationSceneVO> scenes(Long applicationId);

    ApplicationSceneVO scene(Long sceneId);

    ApplicationSceneVO sceneSystem(Long sceneId);

    Long createScene(Long applicationId, CreateApplicationSceneDTO dto);

    void updateScene(Long sceneId, CreateApplicationSceneDTO dto);

    void assertSceneVisible(Long sceneId);

    List<ApplicationMemberVO> members(Long applicationId);

    void addMember(Long applicationId, AddApplicationMemberDTO dto);

    void removeMember(Long applicationId, Long userId);
}
