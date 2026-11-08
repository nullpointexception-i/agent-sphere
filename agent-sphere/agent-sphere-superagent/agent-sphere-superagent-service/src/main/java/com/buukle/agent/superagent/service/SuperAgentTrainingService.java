package com.buukle.agent.superagent.service;

import com.buukle.agent.superagent.dtvo.dto.TrainingMessageDTO;
import com.buukle.agent.superagent.dtvo.vo.TrainingSessionVO;
import com.buukle.agent.superagent.spi.TrainingChatResult;

public interface SuperAgentTrainingService {

    /** Reuse the owner's active TRAINING session or create one on the Worker instance. */
    TrainingSessionVO ensureSession(Long agentId);

    TrainingChatResult send(Long agentId, Long sessionId, TrainingMessageDTO dto);

    void reset(Long agentId, Long sessionId);
}
