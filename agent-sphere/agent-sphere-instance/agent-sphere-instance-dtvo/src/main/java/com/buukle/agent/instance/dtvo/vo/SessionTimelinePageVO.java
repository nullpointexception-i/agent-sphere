package com.buukle.agent.instance.dtvo.vo;

import lombok.Data;

import java.io.Serializable;
import java.util.List;

/** Timeline keyset paging result. */
@Data
public class SessionTimelinePageVO implements Serializable {
    private List<AgentTimelineVO> rows;
    private boolean hasMore;
    /** Cursor for paging forward (oldest seq in rows). */
    private Long oldestSeq;
    /** Cursor for paging backward/catch-up (newest seq in rows). */
    private Long newestSeq;
}