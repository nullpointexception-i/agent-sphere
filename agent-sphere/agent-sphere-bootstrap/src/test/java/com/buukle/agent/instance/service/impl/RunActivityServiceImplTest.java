package com.buukle.agent.instance.service.impl;

import com.buukle.agent.common.exception.BizException;
import com.buukle.agent.instance.domain.vo.RunActivityListVO;
import com.buukle.agent.instance.domain.vo.RunActivitySummaryVO;
import com.buukle.agent.instance.domain.vo.RunActivityVO;
import com.buukle.agent.instance.repository.AgentToolCallRecordMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.when;

/** 活动列表只返回轻量元数据；大报文按 activityType/id 走详情查询。 */
@ExtendWith(MockitoExtension.class)
class RunActivityServiceImplTest {

    @Mock
    AgentToolCallRecordMapper mapper;

    private RunActivityServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new RunActivityServiceImpl(mapper);
    }

    @Test
    void listReturnsSummaryRows() {
        RunActivitySummaryVO row = new RunActivitySummaryVO();
        row.setId(10L);
        row.setActivityType(RunActivitySummaryVO.ACTIVITY_TYPE_LLM);
        row.setModelName("deepseek");
        when(mapper.countActivitiesByRun(1L, 2L)).thenReturn(1);
        when(mapper.selectActivitiesByRun(1L, 2L, 10, 0)).thenReturn(List.of(row));

        RunActivityListVO result = service.listByRun(1L, 2L, 0, 10);

        assertEquals(1, result.getTotal());
        assertEquals(1, result.getRecords().size());
        assertEquals("deepseek", result.getRecords().get(0).getModelName());
    }

    @Test
    void detailLoadsLlmOrToolByType() {
        RunActivityVO llm = new RunActivityVO();
        llm.setId(10L);
        llm.setRequestBody("{\"messages\":[]}");
        when(mapper.selectLlmActivityDetail(1L, 2L, 10L)).thenReturn(llm);

        RunActivityVO tool = new RunActivityVO();
        tool.setId(20L);
        tool.setArtifact("{\"ok\":true}");
        when(mapper.selectToolActivityDetail(1L, 2L, 20L)).thenReturn(tool);

        assertEquals(llm, service.getActivity(1L, 2L, RunActivitySummaryVO.ACTIVITY_TYPE_LLM, 10L));
        assertEquals(tool, service.getActivity(1L, 2L, RunActivitySummaryVO.ACTIVITY_TYPE_TOOL, 20L));
    }

    @Test
    void unknownTypeOrMissingDetailIsRejected() {
        assertThrows(BizException.class,
                () -> service.getActivity(1L, 2L, "unknown", 10L));
        when(mapper.selectLlmActivityDetail(1L, 2L, 10L)).thenReturn(null);
        assertThrows(BizException.class,
                () -> service.getActivity(1L, 2L, RunActivitySummaryVO.ACTIVITY_TYPE_LLM, 10L));
    }
}
