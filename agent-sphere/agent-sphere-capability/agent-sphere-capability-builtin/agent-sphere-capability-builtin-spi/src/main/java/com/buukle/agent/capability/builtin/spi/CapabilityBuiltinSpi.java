package com.buukle.agent.capability.builtin.spi;

import com.buukle.agent.capability.builtin.dtvo.vo.BuiltinToolVO;
import com.buukle.agent.capability.builtin.dtvo.vo.ParameterVerifyResultVO;
import java.util.List;

public interface CapabilityBuiltinSpi {
    List<BuiltinToolVO> listBuiltinTools();

    List<BuiltinToolVO> listAutoIncludeBuiltinTools();

    String executeBuiltinTool(String toolName, String argumentsJson, Long sessionId, Long runId);

    ParameterVerifyResultVO parameterVerify(String toolName, String argumentsJson);
}
