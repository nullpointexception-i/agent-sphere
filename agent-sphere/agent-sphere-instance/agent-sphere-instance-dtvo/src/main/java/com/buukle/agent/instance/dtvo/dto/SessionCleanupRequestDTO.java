package com.buukle.agent.instance.dtvo.dto;

import lombok.Data;

import java.io.Serializable;

/**
 * Request to manually trigger historical session cleanup.
 *
 * <p>{@code dryRun} defaults to true: cleanup is an <b>irreversible hard delete</b>, so the API is
 * designed to make people inspect the list before deleting.
 */
@Data
public class SessionCleanupRequestDTO implements Serializable {

    /** Only count "how many rows would be deleted" without deleting anything; defaults to true */
    private boolean dryRun = true;
}
