package com.buukle.agent.project.dtvo.enums;

/** Requirement lifecycle values stored in {@code pm_requirement.status}. */
public final class RequirementStatus {

    public static final String DRAFT = "DRAFT";
    public static final String REVIEWING = "REVIEWING";
    public static final String APPROVED = "APPROVED";
    public static final String IN_PROGRESS = "IN_PROGRESS";
    public static final String DONE = "DONE";
    public static final String REJECTED = "REJECTED";

    private RequirementStatus() {
    }
}
