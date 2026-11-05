package com.buukle.agent.superagent.dtvo.enums;

/** Group Router decisions persisted on events and deliveries. */
public final class GroupRouterDecisionType {

    public static final String DIRECT = "DIRECT";
    public static final String RELEVANT = "RELEVANT";
    public static final String IGNORE = "IGNORE";
    public static final String OBSERVE = "OBSERVE";
    public static final String PASS_THROUGH = "PASS_THROUGH";

    private GroupRouterDecisionType() {
    }
}
