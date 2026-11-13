package com.buukle.agent.project.spi;

/**
 * Starts a Project Assistant run from business code (auto analysis, manual analysis, scheduled
 * context sync) without creating a dependency from the project module to the runtime module.
 */
public interface ProjectAssistantRunPort {

    /**
     * Run on the project's canonical assistant session.
     *
     * @return the session/run ids, or null when the assistant is not provisioned
     */
    ProjectAssistantRunResult runCanonical(ProjectAssistantRunCommand command);

    /**
     * Run on the requesting user's fork session (manual actions initiated from the project UI).
     */
    ProjectAssistantRunResult runFork(ProjectAssistantRunCommand command);

    /**
     * Answer a targeted question on behalf of the target user and resume the original run.
     */
    void answerClarification(ProjectClarificationAnswerCommand command);
}
