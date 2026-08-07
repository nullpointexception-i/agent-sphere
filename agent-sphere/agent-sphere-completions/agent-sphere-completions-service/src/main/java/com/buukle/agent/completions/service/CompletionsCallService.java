package com.buukle.agent.completions.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.buukle.agent.completions.domain.AgentCompletionsCall;
import com.buukle.agent.completions.dtvo.CompletionsCallVO;

public interface CompletionsCallService {
    void record(AgentCompletionsCall call);

    Page<CompletionsCallVO> pageByCompletions(Long completionsId, int page, int size);
}
