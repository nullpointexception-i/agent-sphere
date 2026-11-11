package com.buukle.agent.instance.dtvo.enums;

/** Workspace document list scopes. */
public final class DocumentListScope {

    /** Documents created by the current user. */
    public static final String MINE = "mine";

    /** Tenant documents created by other users. */
    public static final String TEAM = "team";

    private DocumentListScope() {
    }
}
