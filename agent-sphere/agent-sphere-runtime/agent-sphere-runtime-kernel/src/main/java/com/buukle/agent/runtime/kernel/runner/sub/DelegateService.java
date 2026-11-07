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
 * Unified delegate execution entry: inline in main, isolated as subagent (single task or parallel DAG).
 *
 * <p>A sub-agent fully inherits the parent agent's tool set and model route; agentRef=instance:&lt;id&gt; is only used to layer
 * that instance's systemPrompt+customInstructions. All errors are returned as {@code {"error":"..."}} JSON
 * and never thrown to the main loop.
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
    // P1 _meta namespace: all framework fields converge here; top-level business keys are never read or written
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
    // P2 three-state verdict (lane output contract)
    private static final String VERDICT_COMPUTED = "COMPUTED";
    private static final String VERDICT_UPSTREAM_MISSING = "UPSTREAM_MISSING";
    private static final String VERDICT_UNVERIFIABLE = "CLAIM_UNVERIFIABLE";
    private static final java.util.Set<String> VALID_VERDICTS =
            java.util.Set.of(VERDICT_COMPUTED, VERDICT_UPSTREAM_MISSING, VERDICT_UNVERIFIABLE);
    // P1 error classification (failure attribution and retry decisions)
    private static final String CAT_UPSTREAM_MISSING = "UPSTREAM_MISSING";
    private static final String CAT_TIMEOUT = "TIMEOUT";
    private static final String CAT_MODEL_NO_OUTPUT = "MODEL_NO_OUTPUT";
    private static final String CAT_FORMAT_VIOLATION = "FORMAT_VIOLATION";
    private static final String CAT_TOOL_ERROR = "TOOL_ERROR";
    private static final String CAT_INTERRUPTED = "INTERRUPTED";
    private static final int DEFAULT_RETRY_LIMIT = 1;
    // FIX-2 failure stage classification: used by parent-side decisions to distinguish "not dispatched / upstream stranded / no output / tool error / content downgraded".
    private static final String STAGE_NOT_DISPATCHED = "NOT_DISPATCHED";
    private static final String STAGE_UPSTREAM_STRANDED = "UPSTREAM_STRANDED";
    private static final String STAGE_NO_OUTPUT = "NO_OUTPUT";
    private static final String STAGE_TOOL_ERROR = "TOOL_ERROR";
    private static final String STAGE_CONTENT_DEGRADED = "CONTENT_DEGRADED";
    /** Upper bound aligned with agent_sub_agent_run.display_name VARCHAR(200). */
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

    /** Single lane: builds one dependency-free task from the goal and goes through exactly the same pipeline as the DAG. */
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
     * Unified lane execution pipeline (shared by single lane and DAG): cycle detection → layered dispatch → bounded retry →
     * generation of the unified aggregation envelope {@code {mode, overall, results[]}}; the lane node schema is identical for success and failure.
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

    /** Unified lane node: `result` = pure business JSON (`_meta` stripped; null on failure), framework metadata goes into `meta`. */
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

    /** Reads the `_meta` block from a lane result (null when absent; the caller applies the strict rule). */
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

    /** Cycle detection for the dependsOn directed graph (decided once before dispatch; a cycle and "upstream failed" are different outcomes). */
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

    /** Dependency-first layered execution without a budget cap. Lanes already executed (regardless of result) are not redispatched — retries are handled by {@link #tryRetryFailed}.
     * Lanes whose dependencies are permanently undelivered are filled with notDispatched (a normal branch, not a top-level error); retry rounds decide whether to re-run them. */
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

    /** FIX-1 topological retry: each round redispatches only lanes "whose dependencies are now delivered"; downstream lanes are redispatched in later rounds only after upstream is confirmed.
     * The budget is a per-lane cap (each lane is retried at most retryLimit times); delivered upstream lanes keep their results for downstream decisions,
     * avoiding the case where parallel execution in the same batch amplifies a recoverable upstream retry into a silently stranded subgraph (P0-2). */
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
            // Clear only the previous results of lanes to be redispatched in this batch; delivered upstream lanes keep theirs for downstream fail-fast decisions.
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
            // P0 fail-fast: do not dispatch when an upstream is undelivered; record UPSTREAM_MISSING directly (distinguishes a "real absence" from an "injected fault")
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
            // P0 output contract taught up front: DAG lanes must carry _meta (business fields at the top level), avoiding burning a retry just to "learn" the contract
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

    /** P3-8 fence marker: whether the raw output was wrapped in ``` code fences (surfaced in the aggregate output so the harness can assert "no fence"). */
    private static boolean hadFence(String rawResult) {
        if (!StringUtils.hasText(rawResult)) {
            return false;
        }
        Matcher matcher = JSON_BLOCK.matcher(rawResult.trim());
        return matcher.find();
    }

    /** P0 output contract footer: lanes with dependencies additionally need the three-state verdict + upstream probes. */
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
     * P1/P2/P3 lane output validation (`_meta` namespace contract `delegate-lane/v1`):
     * the framework reads only the top-level `_meta` and never reads or writes top-level business keys. `_meta` is required and must contain a valid contract+status;
     * lanes with dependencies additionally need the three-state verdict + upstream_visible/upstream_raw probes;
     * count fields are cross-checked against the `-expand` detail; chain must be string[].
     * Violation → UNCERTAIN wrapper (containing key/raw/_meta, touching no fields beyond the original business text) + a targeted retry hint is registered.
     * Framework wrappers (`_meta.framework==true`) skip probe/verdict/content checks and only verify that status is valid.
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
            // chain is a business field (not _meta); only its shape is validated, never rewritten
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
            // Write back the current round: the framework wrapper's retryCount increments on redispatch (R1/R9 counts stay visible)
            stampRetryCount(results, task.key(), retryCount);
            // R1: empty output/parse failure, even when shaped as a framework wrapper, registers a nudge requiring contract JSON next round
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
            // B1: the fence is only recorded as a warning and does not change the verdict (stripping failure goes through the fallback violation path)
            if (Boolean.TRUE.equals(fencedFlags.get(task.key()))) {
                addWarning(results, task.key(), SubAgentConstants.WARNING_FENCED);
            }
            // FIX-3 contentVerdict: framework-side independent content review (does not change status; only exposes "OK but empty content").
            // When the business object is "an empty object with status OK" it is INVALID; when the business object is missing/not an object it is DEGRADED; otherwise OK.
            stampContentVerdict(results, task.key(), node);
        }
        logLane(task.key(), results.get(task.key()), retryCount);
    }

    /** FIX-3 writes the framework-side independent review into `_meta.contentVerdict` (OK|DEGRADED|INVALID) without changing the business status. */
    private static void stampContentVerdict(Map<String, String> results, String key, JsonNode node) {
        JsonNode stored = parseJson(results.getOrDefault(key, ""));
        if (!(stored instanceof ObjectNode obj) || !(obj.get(META_KEY) instanceof ObjectNode metaObj)) {
            return;
        }
        // Business object = the remaining fields after stripping _meta from the top level
        JsonNode business = obj.deepCopy();
        if (business instanceof ObjectNode bobj) {
            bobj.remove(META_KEY);
        }
        String verdict;
        if (!(business instanceof ObjectNode bus) || bus.size() == 0) {
            // No business fields
            if (STATUS_OK.equals(metaObj.path(META_STATUS).asText())) {
                verdict = "INVALID"; // claims OK but has no content -> structural masking
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

    /** Writes back _meta.retryCount (a framework wrapper's retryCount is always 0 when created, so retry rounds must backfill it explicitly). */
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

    /** Appends non-fatal warnings to the stored result's _meta.warnings (B1). */
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

    /** P2 expand-then-aggregate: a single array field plus a single count/len/length field (excluding *token*) must agree. */
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
     * P1-3/P1-4 framework failure wrapper: `key` (correlation) and `raw` (raw-text evidence) are placed at the top level for reconciliation and evidence collection,
     * while all other framework fields converge into `_meta` (`framework:true` is the platform-issued marker, never present in business output).
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

    /** FIX-2 failure stage classification: mapped from errorCategory for parent-side topology decisions (distinct from the self-reported status). */
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
     * P0 delivery gating (`_meta` namespace): only an explicit {@code _meta.status=="OK"} counts as delivered.
     * Anything without a `_meta` block is UNCERTAIN (strict rule, incompatible with the old assumption that "bare business JSON means delivered").
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
                // display_name is persisted as VARCHAR(200): never use the full goal as the display name; abbreviate to the key or a truncated title
                .displayName(safeDisplayName(displayName, key))
                .description(goal)
                .parametersSchemaJson(SubAgentConstants.DELEGATE_SCHEMA)
                .execBinding(binding)
                .build();
    }

    private static String safeDisplayName(String displayName, String key) {
        String base = StringUtils.hasText(displayName)
                ? displayName : (StringUtils.hasText(key) ? key : SubAgentConstants.DELEGATE_TOOL);
        // Single-line + truncate: keep goal/multi-line text from exceeding VARCHAR(200)
        String singleLine = base.replaceAll("\\s+", " ").trim();
        return singleLine.length() > DISPLAY_NAME_MAX ? singleLine.substring(0, DISPLAY_NAME_MAX) : singleLine;
    }

    /** agentRef=instance:&lt;id&gt; → that instance's systemPrompt+customInstructions; an empty reference returns null. */
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
     * R4 upstream minimization + recursion removal: take only the upstream lane's business payload (strip `_meta`, which itself contains the upstream's
     * `upstream_raw`, otherwise at depth d the escaping layers and size would grow almost exponentially), and weaken least privilege.
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
