package com.buukle.agent.runtime.orchestration.superagent;

import com.buukle.agent.instance.dtvo.vo.InstanceVO;
import com.buukle.agent.instance.spi.InstanceSpi;
import com.buukle.agent.runtime.kernel.port.vo.RuntimeTool;
import com.buukle.agent.runtime.orchestration.pipeline.ContextPreparer;
import com.buukle.agent.superagent.spi.WorkerToolView;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The Router only needs tool names + descriptions to judge Worker feasibility. */
class WorkerCapabilityServiceImplTest {

    private final InstanceSpi instanceSpi = mock(InstanceSpi.class);
    private final ContextPreparer preparer = mock(ContextPreparer.class);
    private final WorkerCapabilityServiceImpl service =
            new WorkerCapabilityServiceImpl(instanceSpi, preparer);

    @Test
    void mapsNameAndTruncatesDescription() {
        InstanceVO instance = new InstanceVO();
        instance.setId(3L);
        when(instanceSpi.getInstance(3L)).thenReturn(instance);
        RuntimeTool tool = RuntimeTool.builder()
                .llmToolName("builtin_2")
                .displayNameCn("网页搜索")
                .displayNameEn("Web Search")
                .description("d".repeat(200))
                .build();
        when(preparer.previewTools(instance)).thenReturn(List.of(tool));

        List<WorkerToolView> tools = service.describe(3L);

        assertEquals(1, tools.size());
        assertEquals("网页搜索", tools.get(0).name());
        assertEquals(120, tools.get(0).description().length());
    }

    @Test
    void emptyWhenNoWorkerInstance() {
        assertTrue(service.describe(null).isEmpty());
        assertTrue(service.describe(0L).isEmpty());
    }

    @Test
    void emptyWhenInstanceMissing() {
        when(instanceSpi.getInstance(9L)).thenReturn(null);

        assertTrue(service.describe(9L).isEmpty());
    }

    @Test
    void cachesSnapshotPerInstance() {
        InstanceVO instance = new InstanceVO();
        instance.setId(4L);
        when(instanceSpi.getInstance(4L)).thenReturn(instance);
        when(preparer.previewTools(instance)).thenReturn(List.of());

        service.describe(4L);
        service.describe(4L);

        verify(preparer, times(1)).previewTools(instance);
    }
}
