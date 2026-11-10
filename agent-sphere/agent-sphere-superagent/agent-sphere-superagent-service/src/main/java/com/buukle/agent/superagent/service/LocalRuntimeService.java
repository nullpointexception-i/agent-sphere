package com.buukle.agent.superagent.service;

import com.buukle.agent.superagent.dtvo.dto.CreateLocalRuntimeDTO;
import com.buukle.agent.superagent.dtvo.dto.LocalRuntimeHeartbeatDTO;
import com.buukle.agent.superagent.dtvo.dto.UpdateLocalRuntimeDTO;
import com.buukle.agent.superagent.dtvo.vo.IssuedLocalRuntimeTokenVO;
import com.buukle.agent.superagent.dtvo.vo.LocalRuntimeVO;

import java.util.List;

/** Desktop runtime registry and presence. */
public interface LocalRuntimeService {

    IssuedLocalRuntimeTokenVO create(CreateLocalRuntimeDTO dto);

    List<LocalRuntimeVO> list();

    LocalRuntimeVO update(Long id, UpdateLocalRuntimeDTO dto);

    IssuedLocalRuntimeTokenVO rotateToken(Long id);

    void delete(Long id);

    LocalRuntimeVO me(Long runtimeId);

    LocalRuntimeVO heartbeat(Long runtimeId, LocalRuntimeHeartbeatDTO dto);
}
