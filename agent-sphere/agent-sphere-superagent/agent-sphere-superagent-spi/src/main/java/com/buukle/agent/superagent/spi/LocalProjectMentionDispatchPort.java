package com.buukle.agent.superagent.spi;

/** Creates a local task for one project requirement-comment mention. */
public interface LocalProjectMentionDispatchPort {

    LocalProjectMentionDispatchResult dispatch(LocalProjectMentionCommand command);
}
