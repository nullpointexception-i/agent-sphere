package com.buukle.agent.project.dtvo.enums;

/** Lifecycle of one canonical baseline analysis batch. */
public enum ProjectAnalysisBatchStatus {
    PENDING,
    RUNNING,
    COMPLETED,
    FAILED,
    CANCELLED;

    public String value() {
        return name();
    }
}
