package com.buukle.agent.capability.skill.exception;

import com.buukle.agent.common.exception.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum CapabilitySkillErrorCode implements ErrorCode {
    SKILL_NOT_FOUND("A0022", "Skill不存在", "请检查Skill ID"),
    SKILL_NOT_PUBLIC("A0023", "Skill未公开", "该Skill未在Hub公开，无法安装"),
    SKILL_FORBIDDEN("A0024", "无权操作他人Skill", "只能操作自己创建的Skill"),
    SKILL_EXECUTION_FAILED("B0011", "Skill执行失败", "Skill执行异常，请稍后重试");

    private final String code;
    private final String message;
    private final String userTip;
}
