package com.buukle.agent.instance.dtvo.vo;

import lombok.Data;

import java.io.Serializable;
import java.util.List;

/** Timeline keyset 分页结果。 */
@Data
public class SessionTimelinePageVO implements Serializable {
    private List<AgentTimelineVO> rows;
    private boolean hasMore;
    /** 便于向前翻页的光标（rows 最旧 seq）。 */
    private Long oldestSeq;
    /** 便于向后/补档的光标（rows 最新 seq）。 */
    private Long newestSeq;
}