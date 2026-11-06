package com.buukle.agent.runtime.orchestration.superagent;

import com.buukle.agent.instance.dtvo.vo.InstanceVO;
import com.buukle.agent.instance.spi.InstanceSpi;
import com.buukle.agent.runtime.kernel.port.vo.RuntimeTool;
import com.buukle.agent.runtime.orchestration.pipeline.ContextPreparer;
import com.buukle.agent.superagent.spi.WorkerCapabilityPort;
import com.buukle.agent.superagent.spi.WorkerToolView;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Worker capability snapshot for the Router.
 *
 * <p>Uses the same {@link ContextPreparer#previewTools} resolution as a real Worker run,
 * so the answer cannot drift from what the Worker would actually receive. The payload is
 * intentionally reduced to name + description — the Router only needs feasibility.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WorkerCapabilityServiceImpl implements WorkerCapabilityPort {

    private static final long CACHE_TTL_MS = 60_000L;
    private static final int MAX_TOOLS = 40;
    private static final int MAX_DESCRIPTION_CHARS = 120;

    private final InstanceSpi instanceSpi;
    private final ContextPreparer contextPreparer;
    private final Map<Long, CachedSnapshot> cache = new LinkedHashMap<>();

    @Override
    public List<WorkerToolView> describe(Long workerInstanceId) {
        if (workerInstanceId == null || workerInstanceId <= 0) {
            return List.of();
        }
        long now = System.currentTimeMillis();
        synchronized (cache) {
            CachedSnapshot cached = cache.get(workerInstanceId);
            if (cached != null && cached.expiresAt > now) {
                return cached.tools;
            }
        }
        List<WorkerToolView> tools = load(workerInstanceId);
        synchronized (cache) {
            cache.put(workerInstanceId, new CachedSnapshot(tools, now + CACHE_TTL_MS));
            if (cache.size() > 200) {
                cache.clear();
            }
        }
        return tools;
    }

    private List<WorkerToolView> load(Long workerInstanceId) {
        try {
            InstanceVO instance = instanceSpi.getInstance(workerInstanceId);
            if (instance == null) {
                return List.of();
            }
            List<RuntimeTool> tools = contextPreparer.previewTools(instance);
            List<WorkerToolView> views = new ArrayList<>();
            for (RuntimeTool tool : tools) {
                if (views.size() >= MAX_TOOLS) {
                    break;
                }
                views.add(view(tool));
            }
            return views;
        } catch (Exception e) {
            log.warn("Worker capability snapshot failed: instance={}, error={}",
                    workerInstanceId, e.getMessage());
            return List.of();
        }
    }

    private static WorkerToolView view(RuntimeTool tool) {
        String name = tool.getDisplayNameCn();
        if (name == null || name.isBlank()) {
            name = tool.getDisplayNameEn();
        }
        if (name == null || name.isBlank()) {
            name = tool.getLlmToolName();
        }
        String description = tool.getDescription();
        if (description == null || description.isBlank()) {
            description = "";
        } else if (description.length() > MAX_DESCRIPTION_CHARS) {
            description = description.substring(0, MAX_DESCRIPTION_CHARS);
        }
        return new WorkerToolView(name, description);
    }

    private record CachedSnapshot(List<WorkerToolView> tools, long expiresAt) {
    }
}
