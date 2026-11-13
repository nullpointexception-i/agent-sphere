package com.buukle.agent.project.service;

import com.buukle.agent.project.event.ProjectRequirementChangedEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Real-time wake-up for the project baseline pipeline.
 *
 * <p>Writes only nudge the scheduler; the scheduled micro-batch scan remains the compensation
 * path, so a missed wake never loses an event.
 */
@Service
@RequiredArgsConstructor
public class ProjectAutoAnalysisListener {

    private final ProjectBaselineService baselineService;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onChanged(ProjectRequirementChangedEvent event) {
        if (event == null || event.projectId() == null) {
            return;
        }
        baselineService.wake(event.projectId());
    }
}
