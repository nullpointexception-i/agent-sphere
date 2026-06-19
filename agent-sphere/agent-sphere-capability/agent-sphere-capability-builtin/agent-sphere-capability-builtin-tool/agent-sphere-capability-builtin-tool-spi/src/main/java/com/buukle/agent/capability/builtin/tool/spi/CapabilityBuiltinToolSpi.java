package com.buukle.agent.capability.builtin.tool.spi;

import com.buukle.agent.capability.builtin.dtvo.enums.BuiltinToolEnum;
import com.buukle.agent.capability.builtin.dtvo.vo.ParameterVerifyResultVO;
import com.buukle.agent.capability.builtin.tool.spi.dtvo.ExecuteContext;
import com.buukle.agent.capability.builtin.tool.spi.dtvo.ExecuteResult;
import com.buukle.agent.capability.builtin.tool.spi.dtvo.ToolInfoVO;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Validation;
import jakarta.validation.Validator;

import java.util.Set;
import java.util.stream.Collectors;

public interface CapabilityBuiltinToolSpi {
    BuiltinToolEnum getToolType();
    ToolInfoVO getInfo();
    Class<? extends ExecuteContext> getContextType();
    ExecuteResult execute(ExecuteContext ctx);
    Class<? extends ExecuteResult> getResultType();
    boolean needConfig();

    default ParameterVerifyResultVO parameterVerify(String argumentsJson) {
        try {
            ObjectMapper mapper = new ObjectMapper();
            ExecuteContext ctx = mapper.readValue(argumentsJson, getContextType());

            Validator validator = Validation.buildDefaultValidatorFactory().getValidator();
            Set<jakarta.validation.ConstraintViolation<ExecuteContext>> violations = validator.validate(ctx);

            if (!violations.isEmpty()) {
                String message = violations.stream()
                        .map(v -> v.getPropertyPath() + ": " + v.getMessage())
                        .collect(Collectors.joining("; "));
                return new ParameterVerifyResultVO(false, message);
            }

            return new ParameterVerifyResultVO(true, null);
        } catch (Exception e) {
            return new ParameterVerifyResultVO(false, e.getMessage());
        }
    }
}
