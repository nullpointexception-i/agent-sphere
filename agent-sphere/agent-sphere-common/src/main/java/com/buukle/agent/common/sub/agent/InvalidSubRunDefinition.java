package com.buukle.agent.common.sub.agent;

/** Descriptive error for a failed skill definition parse/validation. */
public class InvalidSubRunDefinition extends RuntimeException {

    public InvalidSubRunDefinition(String message) {
        super(message);
    }
}