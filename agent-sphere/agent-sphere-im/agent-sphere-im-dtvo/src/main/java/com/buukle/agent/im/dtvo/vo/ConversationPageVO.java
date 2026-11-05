package com.buukle.agent.im.dtvo.vo;

import lombok.Data;

import java.io.Serializable;
import java.util.List;

/** Cursor page for the conversation list. Pinned rows are returned separately. */
@Data
public class ConversationPageVO implements Serializable {
    private List<ConversationSummaryVO> pinned;
    private List<ConversationSummaryVO> rows;
    private Boolean hasMore;
    private String nextCursor;
}
