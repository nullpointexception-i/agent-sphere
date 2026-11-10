package com.buukle.agent.im.service;

import com.buukle.agent.im.dtvo.dto.AnswerClarificationDTO;
import com.buukle.agent.im.dtvo.vo.ClarificationAnswerVO;

public interface ImClarificationService {

    ClarificationAnswerVO answer(Long conversationId, String inputId,
                                 AnswerClarificationDTO dto);
}
