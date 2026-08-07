package com.buukle.agent.completions.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.buukle.agent.completions.domain.AgentCompletions;
import com.buukle.agent.completions.domain.AgentCompletionsPrompt;
import com.buukle.agent.completions.dtvo.CompletionsPromptVO;
import com.buukle.agent.completions.dtvo.CreatePromptDTO;
import com.buukle.agent.completions.dtvo.enums.CompletionsEnum;
import com.buukle.agent.completions.exception.CompletionsErrorCode;
import com.buukle.agent.completions.repository.CompletionsMapper;
import com.buukle.agent.completions.repository.CompletionsPromptMapper;
import com.buukle.agent.completions.service.CompletionsPromptService;
import com.buukle.agent.common.exception.BizException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class CompletionsPromptServiceImpl implements CompletionsPromptService {

    private final CompletionsMapper completionsMapper;
    private final CompletionsPromptMapper promptMapper;

    @Override
    @Transactional
    public CompletionsPromptVO addVersion(Long completionsId, CreatePromptDTO dto) {
        AgentCompletions c = completionsMapper.selectById(completionsId);
        if (c == null) {
            throw new BizException(CompletionsErrorCode.COMPLETIONS_NOT_FOUND);
        }
        Integer maxVersion = promptMapper.selectList(
                        new LambdaQueryWrapper<AgentCompletionsPrompt>()
                                .eq(AgentCompletionsPrompt::getCompletionsId, completionsId)
                                .orderByDesc(AgentCompletionsPrompt::getVersion))
                .stream().findFirst().map(AgentCompletionsPrompt::getVersion).orElse(0);

        AgentCompletionsPrompt prompt = new AgentCompletionsPrompt();
        prompt.setCompletionsId(completionsId);
        prompt.setVersion(maxVersion + 1);
        prompt.setPromptSystem(dto.getPromptSystem());
        prompt.setPromptUser(dto.getPromptUser());
        prompt.setStatus(CompletionsEnum.STATUS_ACTIVE);
        promptMapper.insert(prompt);
        return toVO(prompt);
    }

    @Override
    public List<CompletionsPromptVO> listByCompletions(Long completionsId) {
        return promptMapper.selectList(
                        new LambdaQueryWrapper<AgentCompletionsPrompt>()
                                .eq(AgentCompletionsPrompt::getCompletionsId, completionsId)
                                .orderByAsc(AgentCompletionsPrompt::getVersion))
                .stream().map(this::toVO).toList();
    }

    @Override
    @Transactional
    public void activate(Long completionsId, Long promptId) {
        AgentCompletions c = completionsMapper.selectById(completionsId);
        if (c == null) {
            throw new BizException(CompletionsErrorCode.COMPLETIONS_NOT_FOUND);
        }
        AgentCompletionsPrompt prompt = promptMapper.selectById(promptId);
        if (prompt == null) {
            throw new BizException(CompletionsErrorCode.PROMPT_NOT_FOUND);
        }
        if (!completionsId.equals(prompt.getCompletionsId())) {
            throw new BizException(CompletionsErrorCode.PROMPT_NOT_BELONG);
        }
        c.setActivePromptId(promptId);
        completionsMapper.updateById(c);
    }

    private CompletionsPromptVO toVO(AgentCompletionsPrompt p) {
        CompletionsPromptVO vo = new CompletionsPromptVO();
        vo.setId(p.getId());
        vo.setVersion(p.getVersion());
        vo.setPromptSystem(p.getPromptSystem());
        vo.setPromptUser(p.getPromptUser());
        vo.setRemark(p.getRemark());
        vo.setCreatedAt(p.getCreatedAt() == null ? null : p.getCreatedAt().toString());
        vo.setUpdatedAt(p.getUpdatedAt() == null ? null : p.getUpdatedAt().toString());
        return vo;
    }
}
