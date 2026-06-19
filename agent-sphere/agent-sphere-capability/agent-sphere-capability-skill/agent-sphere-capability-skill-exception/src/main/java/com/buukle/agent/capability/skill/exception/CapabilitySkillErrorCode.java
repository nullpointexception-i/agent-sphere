package com.buukle.agent.capability.skill.exception;

import com.buukle.agent.common.exception.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum CapabilitySkillErrorCode implements ErrorCode {
    SKILL_NOT_FOUND("A0022", "Skill不存在", "请检查Skill ID"),
    SKILL_EXECUTION_FAILED("B0011", "Skill执行失败", "Skill执行异常，请稍后重试");

    private final String code;
    private final String message;
    private final String userTip;
}
