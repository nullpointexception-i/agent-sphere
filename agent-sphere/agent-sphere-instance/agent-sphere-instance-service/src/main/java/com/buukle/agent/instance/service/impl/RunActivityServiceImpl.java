package com.buukle.agent.instance.service.impl;

import com.buukle.agent.common.error.CommonErrorCode;
import com.buukle.agent.common.exception.BizException;
import com.buukle.agent.instance.domain.vo.RunActivityListVO;
import com.buukle.agent.instance.domain.vo.RunActivitySummaryVO;
import com.buukle.agent.instance.domain.vo.RunActivityVO;
import com.buukle.agent.instance.domain.vo.RunUsagePointVO;
import com.buukle.agent.instance.domain.vo.RunUsageSeriesVO;
import com.buukle.agent.instance.exception.InstanceErrorCode;
import com.buukle.agent.instance.repository.AgentLlmInteractionRecordMapper;
import com.buukle.agent.instance.repository.AgentToolCallRecordMapper;
import com.buukle.agent.instance.service.RunActivityService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
public class RunActivityServiceImpl implements RunActivityService {

    private final AgentToolCallRecordMapper toolCallMapper;
    private final AgentLlmInteractionRecordMapper interactionMapper;

    @Override
    public RunActivityListVO listByRun(Long runId, Long sessionId, int offset, int limit) {
        int total = toolCallMapper.countActivitiesByRun(runId, sessionId);
        List<RunActivitySummaryVO> records = toolCallMapper.selectActivitiesByRun(runId, sessionId, limit, offset);
        return new RunActivityListVO(total, records);
    }

    @Override
    public RunUsageSeriesVO listUsageSeries(Long runId, Long sessionId, long afterId, int limit) {
        int size = Math.min(Math.max(limit, 1), 300);
        List<RunUsagePointVO> rows = interactionMapper.selectUsageSeries(runId, sessionId, afterId, size + 1);
        boolean hasMore = rows.size() > size;
        if (hasMore) {
            rows = new ArrayList<>(rows.subList(1, rows.size()));
        }
        return new RunUsageSeriesVO(rows, hasMore);
    }

    @Override
    public RunActivityVO getActivity(Long runId, Long sessionId, String activityType, Long id) {
        if (runId == null || sessionId == null || id == null || activityType == null) {
            throw new BizException(CommonErrorCode.PARAM_INVALID, "runId/sessionId/activityType/id 不能为空");
        }
        RunActivityVO detail;
        if (RunActivitySummaryVO.ACTIVITY_TYPE_LLM.equals(activityType)) {
            detail = toolCallMapper.selectLlmActivityDetail(runId, sessionId, id);
        } else if (RunActivitySummaryVO.ACTIVITY_TYPE_TOOL.equals(activityType)) {
            detail = toolCallMapper.selectToolActivityDetail(runId, sessionId, id);
        } else {
            throw new BizException(CommonErrorCode.PARAM_INVALID, "未知的 activityType: " + activityType);
        }
        if (detail == null) {
            throw new BizException(InstanceErrorCode.RUN_NOT_FOUND, "活动记录不存在");
        }
        return detail;
    }
}
