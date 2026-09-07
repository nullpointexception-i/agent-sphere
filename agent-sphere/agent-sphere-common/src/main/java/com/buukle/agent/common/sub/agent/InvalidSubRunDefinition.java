package com.buukle.agent.common.sub.agent;

/** Skill definition 解析/校验失败的描述性错误。 */
public class InvalidSubRunDefinition extends RuntimeException {

    public InvalidSubRunDefinition(String message) {
        super(message);
    }
}