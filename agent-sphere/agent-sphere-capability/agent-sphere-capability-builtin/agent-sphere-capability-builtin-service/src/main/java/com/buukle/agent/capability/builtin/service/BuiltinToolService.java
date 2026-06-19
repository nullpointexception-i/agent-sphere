package com.buukle.agent.capability.builtin.service;

import com.buukle.agent.capability.builtin.dtvo.vo.BuiltinToolVO;
import com.buukle.agent.capability.builtin.dtvo.vo.ParameterVerifyResultVO;
import com.buukle.agent.capability.builtin.spi.CapabilityBuiltinSpi;
import com.buukle.agent.capability.builtin.tool.spi.CapabilityBuiltinToolSpi;
import com.buukle.agent.capability.builtin.tool.spi.dtvo.ExecuteContext;
import com.buukle.agent.capability.builtin.tool.spi.dtvo.ExecuteResult;
import com.buukle.agent.capability.builtin.tool.spi.dtvo.ToolInfoVO;
import com.buukle.agent.common.exception.BizException;
import com.buukle.agent.common.error.CommonErrorCode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@Primary
@RequiredArgsConstructor
public class BuiltinToolService implements CapabilityBuiltinSpi {

    private final List<CapabilityBuiltinToolSpi> tools;

    @Override
    public List<BuiltinToolVO> listBuiltinTools() {
        return tools.stream().map(t -> {
            ToolInfoVO info = t.getInfo();
            BuiltinToolVO vo = new BuiltinToolVO();
            vo.setId((long) t.getToolType().getId());
            vo.setName(info.getName());
            vo.setDescription(info.getDescription());
            vo.setParamSchema(info.getParamSchema());
            vo.setResponseSchema(info.getResponseSchema());
            vo.setNeedConfig(t.needConfig());
            return vo;
        }).toList();
    }

    @Override
    public List<BuiltinToolVO> listAutoIncludeBuiltinTools() {
        return tools.stream()
            .filter(t -> !t.needConfig())
            .map(t -> {
                ToolInfoVO info = t.getInfo();
                BuiltinToolVO vo = new BuiltinToolVO();
                vo.setId((long) t.getToolType().getId());
                vo.setName(info.getName());
                vo.setDescription(info.getDescription());
                vo.setParamSchema(info.getParamSchema());
                vo.setResponseSchema(info.getResponseSchema());
                vo.setNeedConfig(false);
                return vo;
            }).toList();
    }

    @Override
    public String executeBuiltinTool(String toolName, String argumentsJson, Long sessionId, Long runId) {
        try {
            ObjectMapper mapper = new ObjectMapper();
            for (CapabilityBuiltinToolSpi tool : tools) {
                ToolInfoVO info = tool.getInfo();
                if (!info.getName().equals(toolName)) continue;
                ExecuteContext ctx = mapper.readValue(argumentsJson, tool.getContextType());
                ctx.setSessionId(sessionId);
                ctx.setRunId(runId);
                ExecuteResult result = tool.execute(ctx);
                return mapper.writeValueAsString(result);
            }
            throw new BizException(CommonErrorCode.PARAM_INVALID, "Unknown builtin tool: " + toolName);
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            throw new BizException(CommonErrorCode.INTERNAL_ERROR, "Builtin tool execution failed: " + e.getMessage());
        }
    }

    @Override
    public ParameterVerifyResultVO parameterVerify(String toolName, String argumentsJson) {
        for (CapabilityBuiltinToolSpi tool : tools) {
            ToolInfoVO info = tool.getInfo();
            if (!info.getName().equals(toolName)) continue;
            return tool.parameterVerify(argumentsJson);
        }
        return new ParameterVerifyResultVO(false, "Unknown builtin tool: " + toolName);
    }
}
