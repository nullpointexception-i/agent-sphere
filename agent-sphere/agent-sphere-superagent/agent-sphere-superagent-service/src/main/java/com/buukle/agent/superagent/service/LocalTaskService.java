package com.buukle.agent.superagent.service;

import com.buukle.agent.superagent.dtvo.dto.LocalTaskChunkDTO;
import com.buukle.agent.superagent.dtvo.dto.LocalTaskCompleteDTO;
import com.buukle.agent.superagent.dtvo.dto.LocalTaskFailDTO;
import com.buukle.agent.superagent.dtvo.vo.LocalTaskAckVO;
import com.buukle.agent.superagent.dtvo.vo.LocalTaskVO;

import java.util.List;

/** Task lifecycle for desktop runtimes. */
public interface LocalTaskService {

    LocalTaskAckVO ack(Long runtimeId, Long taskId);

    void chunk(Long runtimeId, Long taskId, LocalTaskChunkDTO dto);

    void complete(Long runtimeId, Long taskId, LocalTaskCompleteDTO dto);

    void fail(Long runtimeId, Long taskId, LocalTaskFailDTO dto);

    void cancel(Long runtimeId, Long taskId);

    List<LocalTaskVO> list(Long runtimeId, List<String> statuses);
}
