package com.buukle.agent.capability.skill.exception;

import com.buukle.agent.common.exception.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum CapabilitySkillErrorCode implements ErrorCode {
    SKILL_NOT_FOUND("A0022", "Skill not found", "Check the skill ID"),
    SKILL_NOT_PUBLIC("A0023", "Skill is not public", "This skill is not public in the hub and cannot be installed"),
    SKILL_FORBIDDEN("A0024", "Cannot operate on another user's skill", "You can only operate on skills you created"),
    SKILL_EXECUTION_FAILED("B0011", "Skill execution failed", "The skill failed; retry later");

    private final String code;
    private final String message;
    private final String userTip;

    @Override
    public String namespace() {
        return "capability.skill";
    }
}
