package com.buukle.agent.completions.service;

import com.buukle.agent.completions.dtvo.CompletionsPromptVO;
import com.buukle.agent.completions.dtvo.CreatePromptDTO;

import java.util.List;

public interface CompletionsPromptService {
    CompletionsPromptVO addVersion(Long completionsId, CreatePromptDTO dto);

    List<CompletionsPromptVO> listByCompletions(Long completionsId);

    void activate(Long completionsId, Long promptId);
}
