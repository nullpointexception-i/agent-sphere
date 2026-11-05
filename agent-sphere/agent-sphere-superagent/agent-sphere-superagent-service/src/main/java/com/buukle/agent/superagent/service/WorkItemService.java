package com.buukle.agent.superagent.service;

import com.buukle.agent.instance.dtvo.vo.SessionTimelinePageVO;
import com.buukle.agent.superagent.dtvo.vo.WorkItemVO;

import java.util.List;

public interface WorkItemService {

    List<WorkItemVO> list(Long superAgentId, String status);

    WorkItemVO get(Long id);

    /** 失败/取消的任务重新排队，attempt + 1。 */
    WorkItemVO retry(Long superAgentId, Long id);

    /** 取消任务；运行中任务会同时停止 Worker Run。 */
    WorkItemVO cancel(Long superAgentId, Long id);

    WorkItemVO confirmSpeculation(Long superAgentId, Long id);

    /** Owner 查看该任务对应 Worker 会话的完整 timeline。 */
    SessionTimelinePageVO workerTimeline(Long superAgentId, Long id,
                                         Long beforeSeq, Long afterSeq, int limit);
}
