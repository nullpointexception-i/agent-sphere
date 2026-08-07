package com.buukle.agent.completions.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.buukle.agent.completions.domain.AgentCompletionsCall;
import com.buukle.agent.completions.dtvo.CompletionsCallVO;
import com.buukle.agent.completions.repository.CompletionsCallMapper;
import com.buukle.agent.completions.service.CompletionsCallService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class CompletionsCallServiceImpl implements CompletionsCallService {

    private final CompletionsCallMapper callMapper;

    @Override
    public void record(AgentCompletionsCall call) {
        callMapper.insert(call);
    }

    @Override
    public Page<CompletionsCallVO> pageByCompletions(Long completionsId, int page, int size) {
        var mpPage = callMapper.selectPage(new Page<>(page, size),
                new LambdaQueryWrapper<AgentCompletionsCall>()
                        .eq(AgentCompletionsCall::getCompletionsId, completionsId)
                        .orderByDesc(AgentCompletionsCall::getId));
        var voPage = new Page<CompletionsCallVO>(mpPage.getCurrent(), mpPage.getSize(), mpPage.getTotal());
        voPage.setRecords(mpPage.getRecords().stream().map(this::toVO).toList());
        return voPage;
    }

    private CompletionsCallVO toVO(AgentCompletionsCall c) {
        CompletionsCallVO vo = new CompletionsCallVO();
        vo.setId(c.getId());
        vo.setCompletionsId(c.getCompletionsId());
        vo.setPromptId(c.getPromptId());
        vo.setInput(c.getInput());
        vo.setOutput(c.getOutput());
        vo.setModel(c.getModel());
        vo.setUsage(c.getUsage());
        vo.setStatus(c.getStatus());
        vo.setCaller(c.getCaller());
        vo.setRemark(c.getRemark());
        vo.setCreatedAt(c.getCreatedAt() == null ? null : c.getCreatedAt().toString());
        return vo;
    }
}
