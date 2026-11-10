package com.buukle.agent.superagent.service.event;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/** Task notification pushed to the desktop runtime SSE stream. */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class LocalRuntimeStreamEvent implements Serializable {

    /** Currently always task_ready. */
    private String type;
    private Long taskId;
    private String status;
}
