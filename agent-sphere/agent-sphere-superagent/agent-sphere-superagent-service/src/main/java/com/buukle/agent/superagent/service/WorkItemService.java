package com.buukle.agent.superagent.service;

import com.buukle.agent.instance.dtvo.vo.SessionTimelinePageVO;
import com.buukle.agent.superagent.dtvo.vo.WorkItemVO;

import java.util.List;

public interface WorkItemService {

    List<WorkItemVO> list(Long superAgentId, String status);

    WorkItemVO get(Long id);

    /** Requeues a failed/cancelled work item and increments attempt. */
    WorkItemVO retry(Long superAgentId, Long id);

    /** Cancels a work item; a running item also stops its Worker Run. */
    WorkItemVO cancel(Long superAgentId, Long id);

    WorkItemVO confirmSpeculation(Long superAgentId, Long id);

    /** Owner views the full timeline of the Worker session for this work item. */
    SessionTimelinePageVO workerTimeline(Long superAgentId, Long id,
                                         Long beforeSeq, Long afterSeq, int limit);
}
