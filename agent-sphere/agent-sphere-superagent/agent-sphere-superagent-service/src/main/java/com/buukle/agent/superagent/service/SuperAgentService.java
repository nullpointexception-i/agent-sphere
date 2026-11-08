package com.buukle.agent.superagent.service;

import com.buukle.agent.superagent.dtvo.dto.CreateSuperAgentDTO;
import com.buukle.agent.superagent.dtvo.vo.SuperAgentInstancesVO;
import com.buukle.agent.superagent.dtvo.vo.SuperAgentPublicVO;
import com.buukle.agent.superagent.dtvo.vo.SuperAgentVO;

import java.util.List;

public interface SuperAgentService {

    SuperAgentVO create(CreateSuperAgentDTO dto);

    List<SuperAgentVO> list();

    List<SuperAgentVO> list(int limit);

    SuperAgentVO get(Long id);

    SuperAgentInstancesVO getInstances(Long id);

    SuperAgentVO update(Long id, CreateSuperAgentDTO dto);

    void delete(Long id);

    SuperAgentVO pause(Long id);

    SuperAgentVO resume(Long id);

    /** Owner/superadmin only: expose or hide the avatar in group creation. */
    SuperAgentVO updateVisibility(Long id, boolean publicVisible);

    /**
     * Active, public avatars owned by the given users. Empty input returns the
     * caller's own public avatars.
     */
    List<SuperAgentPublicVO> listPublic(List<Long> ownerUserIds);
}
