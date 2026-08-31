package com.buukle.agent.common.skill;

/** Skill definition 解析/校验失败的描述性错误。 */
public class InvalidSkillDefinition extends RuntimeException {

    public InvalidSkillDefinition(String message) {
        super(message);
    }
}