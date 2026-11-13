package com.buukle.agent.project.dtvo.enums;

/** Lifecycle of one project-context event in the canonical baseline pipeline. */
public enum ProjectAnalysisStatus {
    PENDING,
    DISPATCHED,
    PROCESSED,
    FAILED,
    SKIPPED;

    public String value() {
        return name();
    }
}
