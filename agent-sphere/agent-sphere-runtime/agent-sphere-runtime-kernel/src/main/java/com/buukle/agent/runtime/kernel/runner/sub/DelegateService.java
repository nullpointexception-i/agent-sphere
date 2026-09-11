package com.buukle.agent.runtime.kernel.runner.sub;

import com.buukle.agent.common.config.AgentRuntimeProperties;
import com.buukle.agent.common.sub.agent.InvalidSubRunDefinition;
import com.buukle.agent.common.sub.agent.ToolRefs;
import com.buukle.agent.instance.dtvo.vo.InstanceVO;
import com.buukle.agent.instance.spi.InstanceSpi;
import com.buukle.agent.runtime.kernel.constants.ExecBindingKeys;
import com.buukle.agent.runtime.kernel.port.SubRunExecutionContext;
import com.buukle.agent.runtime.kernel.port.vo.RuntimeTool;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * delegate 统一执行入口：main 内联、subagent 隔离（单任务或 DAG 并行）。
 *
 * <p>子 Agent 完全继承父 Agent 的工具集与模型路由；agentRef=instance:&lt;id&gt; 仅用于叠加
 * 该实例的 systemPrompt+customInstructions。所有错误以 {@code {"error":"..."}} JSON 返回，
 * 不向主循环抛异常。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DelegateService {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String AGENT_REF_PREFIX = "instance:";
    private static final String KEY_GOAL = "goal";
    private static final String KEY_ARGS = "args";
    private static final String KEY_MODE = "mode";
    private static final String KEY_AGENT_REF = "agentRef";
    private static final String KEY_TASKS = "tasks";
    private static final String KEY_KEY = "key";
    private static final String KEY_DEPENDS_ON = "dependsOn";

    private static final String JSON_KEY_MODE = "mode";
    private static final String JSON_KEY_INSTRUCTIONS = "instructions";
    private static final String JSON_KEY_RESULT = "result";
    private static final String JSON_KEY_RESULTS = "results";
    private static final String JSON_KEY_NOTE = "note";
    private static final String JSON_KEY_ERROR = "error";
    private static final String JSON_KEY_STATUS = "status";
    private static final String JSON_KEY_REASON = "reason";
    private static final String JSON_KEY_DATA = "data";
    // P1 _meta 命名空间：框架字段全部收敛于此，永不读写顶层业务键
    private static final String META_KEY = "_meta";
    private static final String META_CONTRACT = "contract";
    private static final String META_STATUS = "status";
    private static final String META_VERDICT = "verdict";
    private static final String META_ERROR_CATEGORY = "errorCategory";
    private static final String META_UPSTREAM_VISIBLE = "upstream_visible";
    private static final String META_UPSTREAM_RAW = "upstream_raw";
    private static final String META_RETRY_COUNT = "retryCount";
    private static final String META_REASON = "reason";
    private static final String META_FRAMEWORK = "framework";
    private static final String META_KEY_FIELD = "key";
    private static final String META_RAW_FIELD = "raw";
    private static final String MAIN_NOTE = "Execute directly in the current loop.";
    private static final String MODE_PARALLEL_SUBAGENTS = "parallel-subagents";
    private static final String EMPTY_ARGS = "{}";
    private static final String STATUS_OK = "OK";
    private static final String STATUS_ERROR = "ERROR";
    private static final String STATUS_UNCERTAIN = "UNCERTAIN";
    // P2 三态 verdict（lane 输出契约）
    private static final String VERDICT_COMPUTED = "COMPUTED";
    private static final String VERDICT_UPSTREAM_MISSING = "UPSTREAM_MISSING";
    private static final String VERDICT_UNVERIFIABLE = "CLAIM_UNVERIFIABLE";
    private static final java.util.Set<String> VALID_VERDICTS =
            java.util.Set.of(VERDICT_COMPUTED, VERDICT_UPSTREAM_MISSING, VERDICT_UNVERIFIABLE);
    // P1 错误分类（失败归因与重试决策）
    private static final String CAT_UPSTREAM_MISSING = "UPSTREAM_MISSING";
    private static final String CAT_TIMEOUT = "TIMEOUT";
    private static final String CAT_MODEL_NO_OUTPUT = "MODEL_NO_OUTPUT";
    private static final String CAT_FORMAT_VIOLATION = "FORMAT_VIOLATION";
    private static final String CAT_TOOL_ERROR = "TOOL_ERROR";
    private static final String CAT_INTERRUPTED = "INTERRUPTED";
    private static final int DEFAULT_RETRY_LIMIT = 1;
    // FIX-2 失败阶段归类：父级判定用，区分“未派发/上游搁浅/无输出/工具错误/内容降级”。
    private static final String STAGE_NOT_DISPATCHED = "NOT_DISPATCHED";
    private static final String STAGE_UPSTREAM_STRANDED = "UPSTREAM_STRANDED";
    private static final String STAGE_NO_OUTPUT = "NO_OUTPUT";
    private static final String STAGE_TOOL_ERROR = "TOOL_ERROR";
    private static final String STAGE_CONTENT_DEGRADED = "CONTENT_DEGRADED";
    /** 与 agent_sub_agent_run.display_name VARCHAR(200) 对齐的上限。 */
    private static final int DISPLAY_NAME_MAX = 200;
    private static final Pattern JSON_BLOCK = Pattern.compile("(?s)```(?:json)?\\s*(.*?)\\s*```");
    private static final Pattern LAST_JSON_OBJECT = Pattern.compile("(?s)\\{.*\\}");

    private enum StatusVerdict { DELIVERED_OK, FAILED, UNDELIVERED }

    private final ObjectProvider<SessionSubRunner> subRunnerProvider;
    private final InstanceSpi instanceSpi;
    private final AgentRuntimeProperties properties;

    public String execute(String argsJson, SubRunExecutionContext parentCtx, List<RuntimeTool> tools) {
        try {
            JsonNode args = readArgs(argsJson);
            String goal = text(args, KEY_GOAL);
            if (!StringUtils.hasText(goal)) {
                return error("goal is required");
            }
            String mode = text(args, KEY_MODE);
            if (!StringUtils.hasText(mode)) {
                return error("mode is required (main|subagent)");
            }
            JsonNode tasks = args.path(KEY_TASKS);
            boolean hasTasks = tasks.isArray() && !tasks.isEmpty();
            if (hasTasks && !SubAgentConstants.MODE_SUBAGENT.equals(mode)) {
                return error("tasks requires mode=" + SubAgentConstants.MODE_SUBAGENT);
            }
            return switch (mode) {
                case SubAgentConstants.MODE_MAIN -> executeMain(args, goal);
                case SubAgentConstants.MODE_SUBAGENT -> hasTasks
                        ? executeTasks(parentCtx, tools, parseTasks(tasks))
                        : executeTasks(parentCtx, tools, List.of(singleTask(args, goal)));
                default -> error("unknown mode: " + mode);
            };
        } catch (Exception e) {
            log.warn("delegate failed", e);
            return error("delegate failed: " + e.getMessage());
        }
    }

    // ------------------------------------------------------------------ main

    private String executeMain(JsonNode args, String goal) {
        ObjectNode out = JSON.createObjectNode();
        out.put(JSON_KEY_MODE, SubAgentConstants.MODE_MAIN);
        out.put(JSON_KEY_INSTRUCTIONS, goal);
        JsonNode taskArgs = args.path(KEY_ARGS);
        out.set(KEY_ARGS, taskArgs.isObject() ? taskArgs : JSON.createObjectNode());
        out.put(JSON_KEY_NOTE, MAIN_NOTE);
        return out.toString();
    }

    // -------------------------------------------------------------- subagent

    /** 单车道：以 goal 构造一个无依赖的任务，走与 DAG 完全相同的管线。 */
    private DagTask singleTask(JsonNode args, String goal) {
        return new DagTask(SubAgentConstants.DELEGATE_TOOL, goal, text(args, KEY_AGENT_REF),
                List.of(), args.path(KEY_ARGS));
    }

    private List<DagTask> parseTasks(JsonNode tasks) {
        List<DagTask> parsed = new ArrayList<>();
        Set<String> keys = new LinkedHashSet<>();
        for (JsonNode task : tasks) {
            String key = text(task, KEY_KEY);
            String taskGoal = text(task, KEY_GOAL);
            if (!StringUtils.hasText(key)) {
                throw new IllegalArgumentException("each task requires key");
            }
            if (!StringUtils.hasText(taskGoal)) {
                throw new IllegalArgumentException("task " + key + " requires goal");
            }
            if (!keys.add(key)) {
                throw new IllegalArgumentException("duplicate task key: " + key);
            }
            List<String> dependsOn = new ArrayList<>();
            for (JsonNode dep : task.path(KEY_DEPENDS_ON)) {
                if (dep.isTextual() && StringUtils.hasText(dep.asText())) {
                    dependsOn.add(dep.asText());
                }
            }
            parsed.add(new DagTask(key, taskGoal, text(task, KEY_AGENT_REF), dependsOn, task.path(KEY_ARGS)));
        }
        return parsed;
    }

    /**
     * 统一车道执行管线（单车道与 DAG 共用）：环检测 → 分层派发 → 有界重试 →
     * 生成统一聚合信封 {@code {mode, overall, results[]}}；lane 节点 schema 成功/失败一致。
     */
    private String executeTasks(SubRunExecutionContext parentCtx, List<RuntimeTool> tools, List<DagTask> parsed) {
        if (parsed.isEmpty()) {
            return error("no tasks");
        }
        if (parsed.size() > properties.getDelegate().getMaxDagTasks()) {
            return error("too many tasks (max " + properties.getDelegate().getMaxDagTasks() + ")");
        }
        Set<String> keys = new LinkedHashSet<>();
        for (DagTask task : parsed) {
            keys.add(task.key());
        }
        for (DagTask task : parsed) {
            for (String dep : task.dependsOn()) {
                if (!keys.contains(dep)) {
                    return error("unknown dependsOn key: " + dep + " (task " + task.key() + ")");
                }
            }
        }
        SessionSubRunner runner = runner();
        if (runner == null) {
            return error("Sub-agent executor unavailable");
        }

        Map<String, String> results = new ConcurrentHashMap<>();
        Map<String, String> rawOutputs = new ConcurrentHashMap<>();
        Map<String, Long> durations = new ConcurrentHashMap<>();
        Map<String, String> retryHints = new ConcurrentHashMap<>();
        Map<String, Integer> retryCounts = new ConcurrentHashMap<>();
        Map<String, Boolean> fencedFlags = new ConcurrentHashMap<>();
        int maxParallel = Math.max(1, properties.getDelegate().getMaxParallel());
        int retryLimit = Math.max(0, properties.getDelegate().getMaxDagRetries());
        try {
            if (hasCycle(parsed)) {
                return error("cycle detected in tasks.dependsOn");
            }
            runDagLayers(parsed, runner, parentCtx, tools, maxParallel,
                    results, rawOutputs, durations, retryHints, retryCounts, fencedFlags);
            tryRetryFailed(parsed, runner, parentCtx, tools, maxParallel,
                    results, rawOutputs, durations, retryHints, retryCounts, retryLimit, fencedFlags);
        } catch (IllegalArgumentException e) {
            return error(e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return error("parallel-subagents interrupted");
        } catch (Exception e) {
            log.warn("parallel-subagents failed", e);
            return error("parallel-subagents failed: " + e.getMessage());
        }

        boolean multi = parsed.size() > 1;
        ObjectNode out = JSON.createObjectNode();
        out.put(JSON_KEY_MODE, multi ? MODE_PARALLEL_SUBAGENTS : SubAgentConstants.MODE_SUBAGENT);
        ArrayNode resultArray = out.putArray(JSON_KEY_RESULTS);
        JsonNode firstMeta = null;
        for (DagTask task : parsed) {
            String stored = results.getOrDefault(task.key(), "");
            ObjectNode lane = buildLaneNode(task.key(), stored,
                    rawOutputs.getOrDefault(task.key(), ""),
                    durations.getOrDefault(task.key(), 0L),
                    fencedFlags.getOrDefault(task.key(), false));
            resultArray.add(lane);
            if (firstMeta == null) {
                firstMeta = lane.path(SubAgentConstants.LANE_META);
            }
        }
        out.set(SubAgentConstants.ENV_OVERALL, buildOverall(resultArray));
        return out.toString();
    }

    /** 统一 lane 节点：`result`=纯业务 JSON（剥离 `_meta`；失败为 null），框架元数据进 `meta`。 */
    private static ObjectNode buildLaneNode(String key, String stored, String raw, long durationMs, boolean fenced) {
        ObjectNode node = JSON.createObjectNode();
        node.put(SubAgentConstants.META_WRAPPER_KEY, key);
        JsonNode parsed = parseJson(stored);
        JsonNode meta = metaOf(stored);
        boolean framework = meta != null && meta.path(META_FRAMEWORK).asBoolean(false);
        if (framework || parsed == null || !parsed.isObject()) {
            node.putNull(SubAgentConstants.LANE_RESULT);
        } else {
            ObjectNode business = ((ObjectNode) parsed).deepCopy();
            business.remove(META_KEY);
            node.set(SubAgentConstants.LANE_RESULT, business);
        }
        String r = raw != null ? raw : "";
        node.put(SubAgentConstants.LANE_RAW,
                r.length() > 2000 ? r.substring(0, 2000) + "...[truncated]" : r);
        if (meta != null) {
            node.set(SubAgentConstants.LANE_META, meta.deepCopy());
        } else {
            node.putNull(SubAgentConstants.LANE_META);
        }
        node.put(SubAgentConstants.LANE_FENCED, fenced);
        node.put(SubAgentConstants.LANE_DURATION_MS, durationMs);
        int rc = meta != null && meta.path(META_RETRY_COUNT).isInt() ? meta.get(META_RETRY_COUNT).asInt() : 0;
        node.put(SubAgentConstants.LANE_RETRY_COUNT, rc);
        node.put(JSON_KEY_STATUS, metaStatusOr(meta, STATUS_UNCERTAIN));
        return node;
    }

    private static ObjectNode buildOverall(ArrayNode lanes) {
        ObjectNode overall = JSON.createObjectNode();
        ArrayNode failedKeys = JSON.createArrayNode();
        ArrayNode fencedKeys = JSON.createArrayNode();
        int ok = 0;
        for (JsonNode lane : lanes) {
            String key = lane.path(SubAgentConstants.META_WRAPPER_KEY).asText("");
            if (STATUS_OK.equals(lane.path(JSON_KEY_STATUS).asText())) {
                ok++;
            } else {
                failedKeys.add(key);
            }
            if (lane.path(SubAgentConstants.LANE_FENCED).asBoolean(false)) {
                fencedKeys.add(key);
            }
        }
        overall.put(SubAgentConstants.ENV_TOTAL, lanes.size());
        overall.put(SubAgentConstants.ENV_OK, ok);
        overall.put(SubAgentConstants.ENV_FAILED, lanes.size() - ok);
        overall.set(SubAgentConstants.ENV_FAILED_KEYS, failedKeys);
        overall.set(SubAgentConstants.ENV_FENCED_KEYS, fencedKeys);
        return overall;
    }

    // ------------------------------------------------------------------- DAG

    /** 读取 lane 结果中的 `_meta` 块（无则返回 null，由调用方按严格口径处理）。 */
    private static JsonNode metaOf(String stored) {
        JsonNode node = parseJson(stored);
        if (node != null && node.isObject()) {
            JsonNode meta = node.path(META_KEY);
            if (meta.isObject()) {
                return meta;
            }
        }
        return null;
    }

    private static String metaStatusOr(JsonNode meta, String def) {
        if (meta != null && meta.path(META_STATUS).isTextual()) {
            return meta.get(META_STATUS).asText();
        }
        return def;
    }

    /** dependsOn 有向图环检测（派发前一次性判定；环与“上游失败”是两种不同结局）。 */
    private static boolean hasCycle(List<DagTask> parsed) {
        Map<String, List<String>> edges = new java.util.HashMap<>();
        for (DagTask task : parsed) {
            edges.put(task.key(), task.dependsOn() == null ? List.of() : task.dependsOn());
        }
        Set<String> visiting = new HashSet<>();
        Set<String> done = new HashSet<>();
        for (String key : edges.keySet()) {
            if (hasCycleFrom(key, edges, visiting, done)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasCycleFrom(String key, Map<String, List<String>> edges,
                                        Set<String> visiting, Set<String> done) {
        if (done.contains(key)) {
            return false;
        }
        if (!visiting.add(key)) {
            return true;
        }
        for (String dep : edges.getOrDefault(key, List.of())) {
            if (edges.containsKey(dep) && hasCycleFrom(dep, edges, visiting, done)) {
                return true;
            }
        }
        visiting.remove(key);
        done.add(key);
        return false;
    }

    /** 无预算上限的按依赖首次分层执行。已执行过的泳道（不论结果）不再重派发——重试由 {@link #tryRetryFailed} 负责。
     * 依赖永久未交付的泳道填 notDispatched（正常分支，非顶层错误），由重试轮次决定是否补跑。 */
    private void runDagLayers(List<DagTask> parsed, SessionSubRunner runner, SubRunExecutionContext parentCtx,
                                 List<RuntimeTool> tools, int maxParallel, Map<String, String> results,
                                 Map<String, String> rawOutputs, Map<String, Long> durations,
                                 Map<String, String> retryHints, Map<String, Integer> retryCounts,
                                 Map<String, Boolean> fencedFlags) throws InterruptedException {
        Set<String> delivered = new HashSet<>();
        Set<String> tried = new HashSet<>();
        for (DagTask task : parsed) {
            if (laneDelivered(results.getOrDefault(task.key(), "")) == StatusVerdict.DELIVERED_OK) {
                delivered.add(task.key());
            }
        }
        int safetyValve = parsed.size() * 2 + 1;
        for (int i = 0; i < safetyValve && delivered.size() < parsed.size(); i++) {
            List<DagTask> pending = new ArrayList<>();
            for (DagTask task : parsed) {
                if (delivered.contains(task.key())) continue;
                if (tried.contains(task.key())) continue;
                if (!depsDelivered(task, delivered)) continue;
                pending.add(task);
            }
            if (pending.isEmpty()) break;
            runLayer(pending, runner, parentCtx, tools, maxParallel, results, rawOutputs, durations,
                    retryHints, fencedFlags);
            for (DagTask task : pending) {
                tried.add(task.key());
                classifyLane(task, results, retryHints, retryCounts, 0, fencedFlags);
                if (laneDelivered(results.getOrDefault(task.key(), "")) == StatusVerdict.DELIVERED_OK) {
                    delivered.add(task.key());
                }
            }
        }
        for (DagTask task : parsed) {
            if (!tried.contains(task.key()) && !results.containsKey(task.key())) {
                results.put(task.key(), notDispatched(task.key()));
            }
        }
    }

    /** FIX-1 拓扑化重试：每轮只重派“依赖现已交付”的泳道；下游仅在上游确认后才在后续轮次重派。
     * 预算为 per-lane 上限（每泳道至多重试 retryLimit 次）；已交付上游保持结果供下游判定，
     * 避免同批并行把可恢复的上游重试放大成整条子图静默搁浅（P0-2）。 */
    private void tryRetryFailed(List<DagTask> parsed, SessionSubRunner runner, SubRunExecutionContext parentCtx,
                                List<RuntimeTool> tools, int maxParallel, Map<String, String> results,
                                Map<String, String> rawOutputs, Map<String, Long> durations,
                                Map<String, String> retryHints, Map<String, Integer> retryCounts,
                                int retryLimit, Map<String, Boolean> fencedFlags) throws InterruptedException {
        int safetyValve = parsed.size() * 2 + 1;
        for (int pass = 0; pass < safetyValve; pass++) {
            Set<String> delivered = new HashSet<>();
            for (DagTask task : parsed) {
                if (laneDelivered(results.getOrDefault(task.key(), "")) == StatusVerdict.DELIVERED_OK) {
                    delivered.add(task.key());
                }
            }
            List<DagTask> eligible = new ArrayList<>();
            for (DagTask task : parsed) {
                int used = retryCounts.getOrDefault(task.key(), 0);
                if (laneDelivered(results.getOrDefault(task.key(), "")) != StatusVerdict.DELIVERED_OK
                        && depsDelivered(task, delivered)
                        && used < retryLimit) {
                    eligible.add(task);
                }
            }
            if (eligible.isEmpty()) {
                return;
            }
            // 仅清除本批待重派泳道旧结果；已交付上游保持，供下游 fail-fast 判定。
            for (DagTask task : eligible) {
                results.remove(task.key());
                retryCounts.put(task.key(), retryCounts.getOrDefault(task.key(), 0) + 1);
            }
            runLayer(eligible, runner, parentCtx, tools, maxParallel, results, rawOutputs, durations,
                    retryHints, fencedFlags);
            for (DagTask task : eligible) {
                classifyLane(task, results, retryHints, retryCounts,
                        retryCounts.getOrDefault(task.key(), 0), fencedFlags);
            }
        }
    }

    private static boolean depsDelivered(DagTask task, Set<String> delivered) {
        if (task.dependsOn() == null || task.dependsOn().isEmpty()) {
            return true;
        }
        return delivered.containsAll(task.dependsOn());
    }

    private void runLayer(List<DagTask> layer, SessionSubRunner runner, SubRunExecutionContext parentCtx,
                            List<RuntimeTool> tools, int maxParallel, Map<String, String> results,
                            Map<String, String> rawOutputs, Map<String, Long> durations,
                            Map<String, String> retryHints, Map<String, Boolean> fencedFlags) throws InterruptedException {
        Semaphore permits = new Semaphore(maxParallel);
        try (ExecutorService exec = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<?>> futures = new ArrayList<>();
            for (DagTask task : layer) {
                futures.add(exec.submit(() -> {
                    runDagTask(task, runner, parentCtx, tools, permits, results, rawOutputs, durations,
                            retryHints, fencedFlags);
                    return null;
                }));
            }
            for (Future<?> future : futures) {
                try {
                    future.get();
                } catch (Exception e) {
                    log.warn("delegate DAG future failed", e);
                }
            }
        }
    }

    private void runDagTask(DagTask task, SessionSubRunner runner, SubRunExecutionContext parentCtx,
                            List<RuntimeTool> tools, Semaphore permits, Map<String, String> results,
                            Map<String, String> rawOutputs, Map<String, Long> durations,
                            Map<String, String> retryHints, Map<String, Boolean> fencedFlags) {
        long startMs = System.currentTimeMillis();
        try {
            permits.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            results.put(task.key(), frameworkError(task.key(), STATUS_ERROR, CAT_INTERRUPTED, "interrupted", 0, ""));
            return;
        }
        try {
            // P0 fail-fast：上游未交付就不派发，直接记 UPSTREAM_MISSING（可区分“真缺失”与“注入故障”）
            for (String dep : task.dependsOn()) {
                if (laneDelivered(results.getOrDefault(dep, "")) != StatusVerdict.DELIVERED_OK) {
                    results.put(task.key(), upstreamMissing(task.key(), dep));
                    log.info("delegate lane skipped: key={} missing upstream dep={}", task.key(), dep);
                    return;
                }
            }
            String system = resolveAgentSystem(task.agentRef());
            JsonNode upstream = upstreamDataForDependencies(task, results);
            String hint = retryHints.get(task.key());
            // P0 输出契约前置教学：DAG lane 必须带 _meta（业务字段放顶层），避免烧一次重试来“学会”契约
            String goal = task.goal() + contractFooter(task)
                    + (StringUtils.hasText(hint) ? "\n\n" + hint : "");
            RuntimeTool tool = buildTaskTool(task.key(), goal, system, null,
                    ContactEnvelope.of(task.key(), task.args(), upstream));
            Object contact = tool.getExecBinding().get(ExecBindingKeys.DELEGATE_CONTACT);
            log.info("delegate lane dispatch: key={} deps={} contactBytes={}",
                    task.key(), task.dependsOn(),
                    contact != null ? contact.toString().length() : 0);
            String rawResult = runner.execute(tool, argsForTask(task.args()), parentCtx, tools);
            durations.put(task.key(), System.currentTimeMillis() - startMs);
            rawOutputs.put(task.key(), rawResult != null ? rawResult : "");
            fencedFlags.put(task.key(), hadFence(rawResult));
            results.put(task.key(), normalizeStructuredResult(rawResult, task.key()));
        } catch (Exception e) {
            log.warn("delegate DAG task {} failed", task.key(), e);
            durations.put(task.key(), System.currentTimeMillis() - startMs);
            results.put(task.key(), frameworkError(task.key(), STATUS_ERROR, CAT_TOOL_ERROR, e.getMessage(), 0, ""));
        } finally {
            permits.release();
        }
    }

    /** P3-8 围栏标记：原始输出是否带 ``` 代码块围栏（聚合输出透出，供 harness 断言“无围栏”）。 */
    private static boolean hadFence(String rawResult) {
        if (!StringUtils.hasText(rawResult)) {
            return false;
        }
        Matcher matcher = JSON_BLOCK.matcher(rawResult.trim());
        return matcher.find();
    }

    /** P0 输出契约 footer：有依赖的泳道另需 verdict 三态 + 上游探针。 */
    private static String contractFooter(DagTask task) {
        boolean hasDeps = task.dependsOn() != null && !task.dependsOn().isEmpty();
        StringBuilder sb = new StringBuilder(
                "\n\n[OUTPUT CONTRACT ").append(SubAgentConstants.LANE_CONTRACT)
                .append(" — platform meta-instruction, does NOT override your task contract. ")
                .append("Your result MUST be a JSON object with a top-level \"_meta\" object: ")
                .append("{\"contract\":\"").append(SubAgentConstants.LANE_CONTRACT)
                .append("\",\"status\":\"OK|ERROR|UNCERTAIN\"");
        if (hasDeps) {
            sb.append(",\"verdict\":\"COMPUTED|UPSTREAM_MISSING|CLAIM_UNVERIFIABLE\",")
                    .append("\"upstream_visible\":<boolean>,")
                    .append("\"upstream_raw\":\"<quote CONTACT_ENVELOPE upstream JSON verbatim>\"");
        }
        sb.append("}. Keep all business fields at top level, outside _meta.]");
        return sb.toString();
    }

    /**
     * P1/P2/P3 lane 输出校验（`_meta` 命名空间契约 `delegate-lane/v1`）：
     * 框架只读顶层 `_meta`，永不读写顶层业务键。`_meta` 必填且须含合法 contract+status；
     * 有依赖的泳道另需 verdict 三态 + upstream_visible/upstream_raw 探针；
     * 计数类字段-expand 明细交叉校验；chain 须为 string[]。
     * 违例 → UNCERTAIN 包装（含 key/raw/_meta，不碰业务原文之外的字段）+ 登记针对性 retry hint。
     * 框架包装器（`_meta.framework==true`）跳过探针/verdict/内容检查，只验 status 合法。
     */
    private void classifyLane(DagTask task, Map<String, String> results,
                              Map<String, String> retryHints, Map<String, Integer> retryCounts,
                              int retryCount, Map<String, Boolean> fencedFlags) {
        String stored = results.getOrDefault(task.key(), "");
        JsonNode node = parseJson(stored);
        if (node == null || !node.isObject()) {
            results.put(task.key(), frameworkError(task.key(), STATUS_UNCERTAIN, CAT_MODEL_NO_OUTPUT,
                    "empty or non-JSON lane output", retryCount, stored));
            retryHints.put(task.key(), retryHintFor(List.of(
                    "lane output must be a JSON object; even when you cannot compute, emit the contracted JSON with status UNCERTAIN")));
            logLane(task.key(), results.get(task.key()), retryCount);
            return;
        }
        boolean hasDeps = task.dependsOn() != null && !task.dependsOn().isEmpty();
        JsonNode meta = node.path(META_KEY);
        boolean framework = meta.isObject() && meta.path(META_FRAMEWORK).asBoolean(false);
        List<String> violations = new ArrayList<>();
        if (!meta.isObject()) {
            violations.add("missing _meta block (contract "
                    + SubAgentConstants.LANE_CONTRACT + "); framework fields must live under top-level _meta,"
                    + " business fields stay top-level");
        } else {
            JsonNode contract = meta.path(META_CONTRACT);
            if (!contract.isTextual() || !SubAgentConstants.LANE_CONTRACT.equals(contract.asText())) {
                violations.add("unknown/missing _meta.contract (must be "
                        + SubAgentConstants.LANE_CONTRACT + ")");
            }
            JsonNode status = meta.path(META_STATUS);
            if (!status.isTextual()
                    || (!STATUS_OK.equals(status.asText())
                    && !STATUS_ERROR.equals(status.asText())
                    && !STATUS_UNCERTAIN.equals(status.asText()))) {
                violations.add("_meta.status missing/invalid (must be one of OK|ERROR|UNCERTAIN)");
            }
            if (!framework) {
                JsonNode verdict = meta.path(META_VERDICT);
                boolean verdictPresent = verdict.isTextual();
                if (hasDeps || verdictPresent) {
                    if (!verdictPresent || !VALID_VERDICTS.contains(verdict.asText())) {
                        violations.add("_meta.verdict missing/invalid (must be one of "
                                + "COMPUTED|UPSTREAM_MISSING|CLAIM_UNVERIFIABLE)");
                    } else if (STATUS_OK.equals(status.asText()) && !VERDICT_COMPUTED.equals(verdict.asText())) {
                        violations.add("_meta.status OK contradicts _meta.verdict " + verdict.asText());
                    }
                }
                if (hasDeps) {
                    if (!meta.path(META_UPSTREAM_VISIBLE).isBoolean()) {
                        violations.add("_meta.upstream_visible missing/invalid (must be boolean)");
                    }
                    if (!meta.path(META_UPSTREAM_RAW).isTextual()) {
                        violations.add("_meta.upstream_raw missing/invalid (must be string,"
                                + " quote CONTACT_ENVELOPE upstream verbatim)");
                    }
                }
            }
        }
        if (!framework) {
            checkCountConsistency(node, violations);
            // chain 是业务字段（非 _meta），仅校验其形状，不改写
            JsonNode chain = node.path("chain");
            if (!chain.isMissingNode() && !chain.isNull()) {
                if (!chain.isArray()) {
                    violations.add("chain must be an array of strings");
                } else {
                    for (JsonNode el : chain) {
                        if (!el.isTextual()) {
                            violations.add("chain elements must be strings");
                            break;
                        }
                    }
                }
            }
        }
        if (!violations.isEmpty()) {
            results.put(task.key(), uncertainWrapper(task.key(), violations, node, retryCount));
            retryHints.put(task.key(), retryHintFor(violations));
        } else {
            retryHints.remove(task.key());
            // 回写当前轮次：框架包装器的 retryCount 随重派发递增（R1/R9 计数可见）
            stampRetryCount(results, task.key(), retryCount);
            // R1：空输出/解析失败类即使成形为框架包装，也登记 nudge 要求下轮必出契约 JSON
            JsonNode settled = parseJson(results.getOrDefault(task.key(), ""));
            if (settled != null && settled.isObject()) {
                JsonNode settledMeta = settled.path(META_KEY);
                if (settledMeta.isObject() && settledMeta.path(META_FRAMEWORK).asBoolean(false)
                        && CAT_MODEL_NO_OUTPUT.equals(settledMeta.path(META_ERROR_CATEGORY).asText())) {
                    retryHints.put(task.key(), retryHintFor(List.of(
                            "previous output was empty/unparseable; you MUST emit the contracted JSON now,"
                                    + " even if only to report status UNCERTAIN")));
                }
            }
            // B1：围栏仅记录告警，不改判（剥离失败走兜底违约路径）
            if (Boolean.TRUE.equals(fencedFlags.get(task.key()))) {
                addWarning(results, task.key(), SubAgentConstants.WARNING_FENCED);
            }
            // FIX-3 contentVerdict：框架独立内容审定（不改 status，仅透明化“OK 但内容为空”）。
            // 业务对象为“空对象且状态 OK”时记 INVALID，业务对象缺失/非对象记 DEGRADED，否则 OK。
            stampContentVerdict(results, task.key(), node);
        }
        logLane(task.key(), results.get(task.key()), retryCount);
    }

    /** FIX-3 在 `_meta.contentVerdict` 写入框架侧独立审定（OK|DEGRADED|INVALID），不改业务 status。 */
    private static void stampContentVerdict(Map<String, String> results, String key, JsonNode node) {
        JsonNode stored = parseJson(results.getOrDefault(key, ""));
        if (!(stored instanceof ObjectNode obj) || !(obj.get(META_KEY) instanceof ObjectNode metaObj)) {
            return;
        }
        // 业务对象 = 顶层剥离 _meta 后剩余字段
        JsonNode business = obj.deepCopy();
        if (business instanceof ObjectNode bobj) {
            bobj.remove(META_KEY);
        }
        String verdict;
        if (!(business instanceof ObjectNode bus) || bus.size() == 0) {
            // 无业务字段
            if (STATUS_OK.equals(metaObj.path(META_STATUS).asText())) {
                verdict = "INVALID"; // 声称 OK 却无内容 → 结构性掩盖
            } else {
                verdict = "DEGRADED";
            }
        } else if (!STATUS_OK.equals(metaObj.path(META_STATUS).asText())) {
            verdict = "DEGRADED";
        } else {
            verdict = "OK";
        }
        metaObj.put(SubAgentConstants.META_CONTENT_VERDICT, verdict);
        results.put(key, obj.toString());
    }

    /** 回写 _meta.retryCount（框架包装器在创建时 retryCount 恒为 0，重试轮次需显式回填）。 */
    private static void stampRetryCount(Map<String, String> results, String key, int retryCount) {
        JsonNode node = parseJson(results.getOrDefault(key, ""));
        if (node instanceof ObjectNode obj) {
            JsonNode meta = obj.path(META_KEY);
            if (meta instanceof ObjectNode metaObj) {
                metaObj.put(META_RETRY_COUNT, retryCount);
                results.put(key, obj.toString());
            }
        }
    }

    /** 向已存储结果的 _meta.warnings 追加非致命告警（B1）。 */
    private static void addWarning(Map<String, String> results, String key, String warning) {
        JsonNode node = parseJson(results.getOrDefault(key, ""));
        if (node instanceof ObjectNode obj) {
            JsonNode meta = obj.path(META_KEY);
            if (meta instanceof ObjectNode metaObj) {
                com.fasterxml.jackson.databind.node.ArrayNode warnings;
                if (metaObj.path("warnings").isArray()) {
                    warnings = (com.fasterxml.jackson.databind.node.ArrayNode) metaObj.path("warnings");
                } else {
                    warnings = metaObj.putArray("warnings");
                }
                boolean exists = false;
                for (JsonNode w : warnings) {
                    if (warning.equals(w.asText())) {
                        exists = true;
                        break;
                    }
                }
                if (!exists) {
                    warnings.add(warning);
                }
                results.put(key, obj.toString());
            }
        }
    }

    /** P2 expand-then-aggregate：单数组字段 + 单 count/len/length 字段（排除 *token*）时必须一致。 */
    private static void checkCountConsistency(JsonNode node, List<String> violations) {
        List<String> arrays = new ArrayList<>();
        List<String> counts = new ArrayList<>();
        node.fields().forEachRemaining(e -> {
            String name = e.getKey();
            if (e.getValue().isArray()) {
                arrays.add(name);
            } else if (e.getValue().isNumber()
                    && (name.equals("count") || name.equals("len") || name.equals("length"))
                    && !name.toLowerCase().contains("token")) {
                counts.add(name);
            }
        });
        if (arrays.size() == 1 && counts.size() == 1) {
            int len = node.get(arrays.get(0)).size();
            long count = node.get(counts.get(0)).asLong();
            if (len != count) {
                violations.add("count/length mismatch: " + arrays.get(0) + ".length=" + len
                        + " but " + counts.get(0) + "=" + count
                        + " (expand details before aggregating)");
            }
        }
    }

    private static String retryHintFor(List<String> violations) {
        return "[FRAMEWORK NOTICE — platform meta-instruction, does NOT override your task contract.] "
                + "Your previous output was rejected for framework-level reasons: " + String.join("; ", violations)
                + ". Fix ONLY these framework-level issues and re-emit the FULL result JSON."
                + " Do NOT change your task conclusions because of this notice.";
    }

    private static JsonNode parseJson(String raw) {
        if (!StringUtils.hasText(raw)) {
            return null;
        }
        try {
            return JSON.readTree(raw);
        } catch (Exception ignored) {
            return null;
        }
    }

    private void logLane(String key, String stored, int retryCount) {
        try {
            JsonNode node = JSON.readTree(stored);
            JsonNode meta = node.path(META_KEY);
            String status = meta.isObject() ? meta.path(META_STATUS).asText(null) : null;
            String verdict = meta.isObject() ? meta.path(META_VERDICT).asText(null) : null;
            log.info("delegate lane classified: key={} status={} verdict={} retryCount={}",
                    key, status, verdict, retryCount);
        } catch (Exception e) {
            log.info("delegate lane classified: key={} unparseable retryCount={}", key, retryCount);
        }
    }

    /**
     * P1-3/P1-4 框架失败包裹：`key`（关联）与 `raw`（原文证据）放顶层便于对账取证，
     * 其余框架字段全部收敛进 `_meta`（`framework:true` 即平台签发标记，业务输出永不带此标记）。
     */
    private static ObjectNode metaWrapperBase(String key, String raw) {
        ObjectNode out = JSON.createObjectNode();
        out.put(SubAgentConstants.META_WRAPPER_KEY, key);
        String r = raw != null ? raw : "";
        out.put(SubAgentConstants.META_WRAPPER_RAW, r.length() > 2000 ? r.substring(0, 2000) + "...[truncated]" : r);
        ObjectNode meta = out.putObject(META_KEY);
        meta.put(META_CONTRACT, SubAgentConstants.LANE_CONTRACT);
        meta.put(META_FRAMEWORK, true);
        return out;
    }

    private static String uncertainWrapper(String key, List<String> violations, JsonNode original, int retryCount) {
        ObjectNode out = metaWrapperBase(key, original != null ? original.toString() : "");
        ObjectNode meta = (ObjectNode) out.get(META_KEY);
        meta.put(META_STATUS, STATUS_UNCERTAIN);
        meta.put(META_VERDICT, VERDICT_UNVERIFIABLE);
        meta.put(META_ERROR_CATEGORY, CAT_FORMAT_VIOLATION);
        meta.put(META_REASON, String.join("; ", violations));
        meta.put(META_RETRY_COUNT, retryCount);
        return out.toString();
    }

    private static String upstreamMissing(String key, String dep) {
        ObjectNode out = metaWrapperBase(key, "");
        ObjectNode meta = (ObjectNode) out.get(META_KEY);
        meta.put(META_STATUS, STATUS_ERROR);
        meta.put(META_VERDICT, VERDICT_UPSTREAM_MISSING);
        meta.put(META_ERROR_CATEGORY, CAT_UPSTREAM_MISSING);
        meta.put(SubAgentConstants.META_FAILED_STAGE, STAGE_UPSTREAM_STRANDED);
        meta.put(SubAgentConstants.META_DEPENDS_NOT_DELIVERED, dep);
        meta.put(META_REASON, "upstream dependency not delivered: " + dep + " (lane " + key + " not dispatched)");
        meta.put(META_RETRY_COUNT, 0);
        return out.toString();
    }

    private static String notDispatched(String key) {
        ObjectNode out = metaWrapperBase(key, "");
        ObjectNode meta = (ObjectNode) out.get(META_KEY);
        meta.put(META_STATUS, STATUS_UNCERTAIN);
        meta.put(META_VERDICT, VERDICT_UPSTREAM_MISSING);
        meta.put(META_ERROR_CATEGORY, CAT_UPSTREAM_MISSING);
        meta.put(SubAgentConstants.META_FAILED_STAGE, STAGE_NOT_DISPATCHED);
        meta.put(META_REASON, "not dispatched (dependency not ready or cycle): " + key);
        meta.put(META_RETRY_COUNT, 0);
        return out.toString();
    }

    private static String frameworkError(String key, String status, String category, String reason,
                                         int retryCount, String raw) {
        ObjectNode out = metaWrapperBase(key, raw);
        ObjectNode meta = (ObjectNode) out.get(META_KEY);
        meta.put(META_STATUS, status == null ? STATUS_ERROR : status);
        meta.put(META_ERROR_CATEGORY, category);
        meta.put(SubAgentConstants.META_FAILED_STAGE, stageForCategory(category));
        if (StringUtils.hasText(reason)) {
            meta.put(META_REASON, reason);
        }
        meta.put(META_RETRY_COUNT, retryCount);
        return out.toString();
    }

    /** FIX-2 失败阶段归类：按 errorCategory 映射，供父级拓扑级判定（区别于自报 status）。 */
    private static String stageForCategory(String category) {
        if (CAT_MODEL_NO_OUTPUT.equals(category)) {
            return STAGE_NO_OUTPUT;
        }
        if (CAT_TOOL_ERROR.equals(category) || CAT_INTERRUPTED.equals(category)) {
            return STAGE_TOOL_ERROR;
        }
        if (CAT_UPSTREAM_MISSING.equals(category)) {
            return STAGE_UPSTREAM_STRANDED;
        }
        return STAGE_CONTENT_DEGRADED;
    }

    /**
     * P0 交付门控（`_meta` 命名空间）：只有显式 {@code _meta.status=="OK"} 才算交付。
     * 无 `_meta` 块的一律 UNCERTAIN（严格口径，不兼容“裸业务 JSON 即交付”的旧假设）。
     */
    private static StatusVerdict laneDelivered(String result) {
        if (!StringUtils.hasText(result)) {
            return StatusVerdict.UNDELIVERED;
        }
        try {
            JsonNode node = JSON.readTree(result);
            if (node != null && node.isObject()) {
                JsonNode meta = node.path(META_KEY);
                if (meta.isObject() && meta.path(META_STATUS).isTextual()) {
                    String status = meta.get(META_STATUS).asText();
                    if (STATUS_OK.equals(status)) return StatusVerdict.DELIVERED_OK;
                    if (STATUS_UNCERTAIN.equals(status) || STATUS_ERROR.equals(status)) return StatusVerdict.FAILED;
                }
                return StatusVerdict.FAILED;
            }
        } catch (Exception ignored) {
            return StatusVerdict.UNDELIVERED;
        }
        return StatusVerdict.UNDELIVERED;
    }

    // --------------------------------------------------------------- helpers

    private SessionSubRunner runner() {
        return subRunnerProvider.getIfAvailable();
    }

    private RuntimeTool buildTaskTool(String key, String goal, String system, String displayName,
                                       ContactEnvelope contact) {
        Map<String, Object> binding = new HashMap<>();
        binding.put(ExecBindingKeys.DELEGATE_INSTRUCTION, goal);
        if (StringUtils.hasText(system)) {
            binding.put(ExecBindingKeys.DELEGATE_SYSTEM, system);
        }
        if (contact != null) {
            binding.put(ExecBindingKeys.DELEGATE_CONTACT, contact.serialize());
        }
        String ref = StringUtils.hasText(key) && !SubAgentConstants.DELEGATE_TOOL.equals(key)
                ? ToolRefs.agent(key) : ToolRefs.agent(SubAgentConstants.DELEGATE_TOOL);
        return RuntimeTool.builder()
                .capabilityType(SubAgentConstants.CAPABILITY_TYPE_SUB_AGENT)
                .llmToolName(SubAgentConstants.DELEGATE_TOOL)
                .toolRef(ref)
                // display_name 落库为 VARCHAR(200)：禁止把完整 goal 当显示名，缩略为 key 或截断标题
                .displayName(safeDisplayName(displayName, key))
                .description(goal)
                .parametersSchemaJson(SubAgentConstants.DELEGATE_SCHEMA)
                .execBinding(binding)
                .build();
    }

    private static String safeDisplayName(String displayName, String key) {
        String base = StringUtils.hasText(displayName)
                ? displayName : (StringUtils.hasText(key) ? key : SubAgentConstants.DELEGATE_TOOL);
        // 单行化 + 截断：防止 goal/多行文本越过 VARCHAR(200)
        String singleLine = base.replaceAll("\\s+", " ").trim();
        return singleLine.length() > DISPLAY_NAME_MAX ? singleLine.substring(0, DISPLAY_NAME_MAX) : singleLine;
    }

    /** agentRef=instance:&lt;id&gt; → 该实例的 systemPrompt+customInstructions；空引用返回 null。 */
    private String resolveAgentSystem(String agentRef) {
        if (!StringUtils.hasText(agentRef)) {
            return null;
        }
        if (!agentRef.startsWith(AGENT_REF_PREFIX)) {
            throw new InvalidSubRunDefinition("invalid agentRef: " + agentRef);
        }
        Long id;
        try {
            id = Long.parseLong(agentRef.substring(AGENT_REF_PREFIX.length()).trim());
        } catch (NumberFormatException e) {
            throw new InvalidSubRunDefinition("invalid agentRef id: " + agentRef);
        }
        InstanceVO instance = instanceSpi.getInstance(id);
        if (instance == null) {
            throw new InvalidSubRunDefinition("agentRef not found: " + agentRef);
        }
        StringBuilder sb = new StringBuilder();
        if (StringUtils.hasText(instance.getSystemPrompt())) {
            sb.append(instance.getSystemPrompt());
        }
        if (StringUtils.hasText(instance.getCustomInstructions())) {
            if (sb.length() > 0) {
                sb.append("\n\n");
            }
            sb.append(instance.getCustomInstructions());
        }
        return sb.toString();
    }

    private static String argsForTask(JsonNode args) {
        JsonNode taskArgs = args != null && args.isObject() && args.has(KEY_ARGS) ? args.path(KEY_ARGS) : args;
        return taskArgs != null && taskArgs.isObject() ? taskArgs.toString() : EMPTY_ARGS;
    }

    private static String normalizeStructuredResult(String rawResult, String key) {
        String normalized = stripJsonFence(rawResult);
        if (!StringUtils.hasText(normalized)) {
            return frameworkError(key, STATUS_UNCERTAIN, CAT_MODEL_NO_OUTPUT, "empty result", 0, rawResult);
        }
        try {
            JsonNode parsed = JSON.readTree(normalized);
            if (parsed != null && !parsed.isNull()) {
                return parsed.toString();
            }
        } catch (Exception ignored) {
            // continue to extract the last JSON object from the mixed-text response
        }

        Matcher matcher = LAST_JSON_OBJECT.matcher(normalized);
        String lastJson = null;
        while (matcher.find()) {
            lastJson = matcher.group();
        }
        if (lastJson != null) {
            try {
                JsonNode parsed = JSON.readTree(lastJson);
                if (parsed != null && !parsed.isNull()) {
                    return parsed.toString();
                }
            } catch (Exception ignored) {
                // invalid JSON object; fall through to explicit UNCERTAIN
            }
        }

        return frameworkError(key, STATUS_UNCERTAIN, CAT_MODEL_NO_OUTPUT, "non-JSON output rejected", 0, rawResult);
    }

    private static String stripJsonFence(String raw) {
        if (!StringUtils.hasText(raw)) {
            return raw;
        }
        String candidate = raw.trim();
        Matcher matcher = JSON_BLOCK.matcher(candidate);
        String last = null;
        while (matcher.find()) {
            last = matcher.group(1);
        }
        return last != null ? last.trim() : candidate;
    }

    /**
     * R4 上游最小化 + 去递归：只取上游 lane 的业务载荷（剥离 `_meta`，内含上游的
     * `upstream_raw`，否则深度 d 时转义层与体积近似指数膨胀），并弱化最小权限。
     */
    private static JsonNode upstreamDataForDependencies(DagTask task, Map<String, String> results) {
        ObjectNode upstream = JSON.createObjectNode();
        if (task.dependsOn() != null) {
            for (String dep : task.dependsOn()) {
                String value = results.get(dep);
                if (!StringUtils.hasText(value)) {
                    continue;
                }
                try {
                    JsonNode parsed = JSON.readTree(value);
                    if (parsed != null && parsed.isObject()) {
                        ObjectNode business = ((ObjectNode) parsed).deepCopy();
                        business.remove(META_KEY);
                        upstream.set(dep, business);
                    } else if (parsed != null && !parsed.isNull()) {
                        upstream.set(dep, parsed);
                    } else {
                        upstream.put(dep, value);
                    }
                } catch (Exception e) {
                    upstream.put(dep, value);
                }
            }
        }
        JsonNode taskArgs = task.args();
        if (taskArgs != null && taskArgs.isObject()) {
            upstream.set("args", taskArgs);
        }
        return upstream.isEmpty() ? taskArgs : upstream;
    }

    private static JsonNode readArgs(String argsJson) throws Exception {
        if (argsJson == null || argsJson.isBlank()) {
            return JSON.createObjectNode();
        }
        JsonNode node = JSON.readTree(argsJson);
        return node != null && node.isObject() ? node : JSON.createObjectNode();
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isTextual() ? value.asText() : null;
    }

    private static String error(String message) {
        ObjectNode out = JSON.createObjectNode();
        out.put(JSON_KEY_ERROR, message == null ? "error" : message);
        return out.toString();
    }

    private record DagTask(String key, String goal, String agentRef, List<String> dependsOn, JsonNode args) {
    }
}
