package com.buukle.agent.common.constant;

/** Actor types used by Super-Agent audit columns. */
public final class ActorType {

    public static final String USER = "USER";
    public static final String AGENT = "AGENT";
    public static final String SYSTEM = "SYSTEM";
    /** Project-scoped actor (project thread / project-level context). */
    public static final String PROJECT = "PROJECT";

    private ActorType() {
    }
}
