package com.buukle.agent.bootstrap.controller;

import com.buukle.agent.common.config.AgentRuntimeProperties;
import com.buukle.agent.instance.spi.InstanceSpi;
import com.buukle.agent.runtime.kernel.port.SubRunExecutionContext;
import com.buukle.agent.runtime.kernel.port.vo.RuntimeTool;
import com.buukle.agent.runtime.kernel.runner.sub.SessionSubRunner;
import com.buukle.agent.runtime.kernel.runner.sub.DelegateService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class DelegateServiceTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Mock
    ObjectProvider<SessionSubRunner> subRunnerProvider;
    @Mock
    SessionSubRunner subRunner;
    @Mock
    InstanceSpi instanceSpi;

    DelegateService service;

    @BeforeEach
    void setUp() {
        service = new DelegateService(subRunnerProvider, instanceSpi, new AgentRuntimeProperties());
    }

    private SubRunExecutionContext ctx() {
        return SubRunExecutionContext.root(1L, 2L, null);
    }

    /** Build lane output conforming to delegate-lane/v1; business fields at the top level, framework fields only in _meta. */
    private static String laneOk(String businessFieldsJson, String verdict, boolean withProbes) {
        StringBuilder sb = new StringBuilder("{\"_meta\":{\"contract\":\"delegate-lane/v1\",\"status\":\"OK\"");
        if (verdict != null) {
            sb.append(",\"verdict\":\"").append(verdict).append("\"");
        }
        if (withProbes) {
            sb.append(",\"upstream_visible\":true,\"upstream_raw\":\"{}\"");
        }
        sb.append("}");
        if (businessFieldsJson != null && !businessFieldsJson.isBlank()) {
            String trimmed = businessFieldsJson.trim();
            if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
                String inner = trimmed.substring(1, trimmed.length() - 1).trim();
                if (!inner.isEmpty()) {
                    sb.append(",").append(inner);
                }
            }
        }
        sb.append("}");
        return sb.toString();
    }

    private static JsonNode metaOf(JsonNode result) {
        return result.get("_meta");
    }

    @Test
    void main_returnsInstructions_withoutSubRun() throws Exception {
        String out = service.execute("{\"goal\":\"do it\",\"mode\":\"main\"}", ctx(), List.of());
        JsonNode node = JSON.readTree(out);

        assertEquals("main", node.get("mode").asText());
        assertEquals("do it", node.get("instructions").asText());
        verify(subRunnerProvider, never()).getIfAvailable();
        verify(subRunner, never()).execute(any(), anyString(), any(), anyList());
    }

    @Test
    void subagent_delegatesToSessionSubRunner() throws Exception {
        given(subRunnerProvider.getIfAvailable()).willReturn(subRunner);
        given(subRunner.execute(any(RuntimeTool.class), anyString(), any(SubRunExecutionContext.class), anyList()))
                .willReturn(laneOk("{\"result\":\"ok\"}", "COMPUTED", false));

        String out = service.execute("{\"goal\":\"do it\",\"mode\":\"subagent\",\"args\":{\"q\":\"1\"}}",
                ctx(), List.of());

        JsonNode node = JSON.readTree(out);
        assertEquals("subagent", node.get("mode").asText());
        JsonNode lane = node.get("results").get(0);
        // result is pure business JSON (_meta has been stripped into lane.meta)
        assertEquals("{\"result\":\"ok\"}", lane.get("result").toString());
        assertEquals("OK", lane.get("meta").get("status").asText());
        assertEquals(1, node.get("overall").get("ok").asInt());
        verify(subRunner, times(1)).execute(any(RuntimeTool.class), anyString(),
                any(SubRunExecutionContext.class), anyList());
    }

    @Test
    void subagent_preservesTopLevelArgs() throws Exception {
        given(subRunnerProvider.getIfAvailable()).willReturn(subRunner);
        given(subRunner.execute(any(RuntimeTool.class), anyString(), any(SubRunExecutionContext.class), anyList()))
                .willReturn(laneOk("{\"result\":\"ok\"}", "COMPUTED", false));

        service.execute("{\"goal\":\"do it\",\"mode\":\"subagent\",\"args\":{\"q\":\"1\",\"n\":2}}",
                ctx(), List.of());

        verify(subRunner, times(1)).execute(any(RuntimeTool.class), eq("{\"q\":\"1\",\"n\":2}"),
                any(SubRunExecutionContext.class), anyList());
    }

    @Test
    void subagent_embedsArgsIntoContactEnvelope() throws Exception {
        given(subRunnerProvider.getIfAvailable()).willReturn(subRunner);
        given(subRunner.execute(any(RuntimeTool.class), anyString(), any(SubRunExecutionContext.class), anyList()))
                .willAnswer(invocation -> {
                    RuntimeTool tool = invocation.getArgument(0);
                    Object contact = tool.getExecBinding().get(com.buukle.agent.runtime.kernel.constants.ExecBindingKeys.DELEGATE_CONTACT);
                    assertTrue(contact != null && contact.toString().contains("v1-delegate-contact"));
                    assertTrue(contact.toString().contains("\"q\":\"1\""));
                    return laneOk("{\"result\":\"ok\"}", "COMPUTED", false);
                });

        service.execute("{\"goal\":\"do it\",\"mode\":\"subagent\",\"args\":{\"q\":\"1\"}}",
                ctx(), List.of());
    }

    @Test
    void tasks_dagRunsEachKeyInParallel() throws Exception {
        given(subRunnerProvider.getIfAvailable()).willReturn(subRunner);
        given(subRunner.execute(any(RuntimeTool.class), anyString(), any(SubRunExecutionContext.class), anyList()))
                .willReturn(laneOk("{\"data\":{\"result\":\"ok\"}}", null, false));

        String out = service.execute("""
                {"goal":"dag","mode":"subagent","tasks":[
                  {"key":"a","goal":"ga","args":{"x":1}},
                  {"key":"b","goal":"gb","args":{"y":2}}]}""", ctx(), List.of());

        JsonNode node = JSON.readTree(out);
        assertEquals("parallel-subagents", node.get("mode").asText());
        assertEquals(2, node.get("results").size());
        verify(subRunner, times(2)).execute(any(RuntimeTool.class), anyString(),
                any(SubRunExecutionContext.class), anyList());
        verify(subRunner, times(1)).execute(any(RuntimeTool.class), eq("{\"x\":1}"),
                any(SubRunExecutionContext.class), anyList());
        verify(subRunner, times(1)).execute(any(RuntimeTool.class), eq("{\"y\":2}"),
                any(SubRunExecutionContext.class), anyList());
    }

    @Test
    void task_contactEnvelope_carriesArgsAndUpstream() throws Exception {
        given(subRunnerProvider.getIfAvailable()).willReturn(subRunner);
        given(subRunner.execute(any(RuntimeTool.class), anyString(), any(SubRunExecutionContext.class), anyList()))
                .willAnswer(inv -> {
                    RuntimeTool t = inv.getArgument(0);
                    if ("agent:b".equals(t.getToolRef())) {
                        return laneOk("{}", "COMPUTED", true);
                    }
                    return laneOk("{}", null, false);
                });

        String out = service.execute("""
                {"goal":"dag","mode":"subagent","tasks":[
                  {"key":"a","goal":"ga","args":{"seed":7411}},
                  {"key":"b","goal":"gb","args":{"n":6},"dependsOn":["a"]}]}""", ctx(), List.of());

        JsonNode node = JSON.readTree(out);
        assertEquals(2, node.get("results").size());
        assertEquals("OK", node.get("results").get(1).get("status").asText());
        verify(subRunner, times(2)).execute(any(RuntimeTool.class), anyString(),
                any(SubRunExecutionContext.class), anyList());
        // First verify the contact envelope was injected into b (args + upstream), then verify single-level b execution
        org.mockito.ArgumentCaptor<RuntimeTool> captor = org.mockito.ArgumentCaptor.forClass(RuntimeTool.class);
        verify(subRunner, times(2)).execute(captor.capture(), anyString(),
                any(SubRunExecutionContext.class), anyList());
        boolean bContactSeen = false;
        for (RuntimeTool t : captor.getAllValues()) {
            if (t.getExecBinding() != null
                    && t.getExecBinding().get(com.buukle.agent.runtime.kernel.constants.ExecBindingKeys.DELEGATE_CONTACT) != null
                    && t.getExecBinding().get(com.buukle.agent.runtime.kernel.constants.ExecBindingKeys.DELEGATE_CONTACT).toString().contains("\"n\":6")) {
                bContactSeen = true;
                break;
            }
        }
        assertTrue(bContactSeen, "DAG 泳道 b 的 contact envelope 应携带 laneKey/args/upstream");
    }

    @Test
    void task_missingLaneResult_retriedThenMarkedUncertain() throws Exception {
        given(subRunnerProvider.getIfAvailable()).willReturn(subRunner);
        given(subRunner.execute(any(RuntimeTool.class), anyString(), any(SubRunExecutionContext.class), anyList()))
                .willReturn("I could not compute that");

        String out = service.execute("""
                {"goal":"dag","mode":"subagent","tasks":[{"key":"a","goal":"ga"}]}""", ctx(), List.of());

        JsonNode node = JSON.readTree(out);
        JsonNode taskNode = node.get("results").get(0);
        assertEquals("a", taskNode.get("key").asText());
        assertEquals("UNCERTAIN", taskNode.get("meta").get("status").asText());
        // Default maxDagRetries=1 → first attempt + 1 redispatch
        verify(subRunner, times(2)).execute(any(RuntimeTool.class), anyString(),
                any(SubRunExecutionContext.class), anyList());
    }

    @Test
    void tasks_requiresSubagentMode() throws Exception {        String out = service.execute("""
                {"goal":"dag","mode":"main","tasks":[{"key":"a","goal":"ga"}]}""", ctx(), List.of());
        assertTrue(JSON.readTree(out).has("error"));
        verify(subRunner, never()).execute(any(), anyString(), any(), anyList());
    }

    @Test
    void tasks_cycleReturnsError() throws Exception {
        given(subRunnerProvider.getIfAvailable()).willReturn(subRunner);

        String out = service.execute("""
                {"goal":"dag","mode":"subagent","tasks":[
                  {"key":"a","goal":"ga","dependsOn":["b"]},
                  {"key":"b","goal":"gb","dependsOn":["a"]}]}""", ctx(), List.of());

        assertTrue(JSON.readTree(out).has("error"));
        verify(subRunner, never()).execute(any(), anyString(), any(), anyList());
    }

    @Test
    void agentRefResolutionFailure_returnsError() throws Exception {
        given(subRunnerProvider.getIfAvailable()).willReturn(subRunner);
        given(instanceSpi.getInstance(999L)).willReturn(null);

        String out = service.execute(
                "{\"goal\":\"g\",\"mode\":\"subagent\",\"agentRef\":\"instance:999\"}", ctx(), List.of());

        // Unified envelope: the error goes to lane.meta, overall failed=1
        JsonNode node = JSON.readTree(out);
        assertEquals(1, node.get("overall").get("failed").asInt());
        JsonNode lane = node.get("results").get(0);
        assertEquals("ERROR", lane.get("meta").get("status").asText());
        verify(subRunner, never()).execute(any(), anyString(), any(), anyList());
    }

    @Test
    void agentRefInvalidFormat_returnsError() throws Exception {
        given(subRunnerProvider.getIfAvailable()).willReturn(subRunner);

        String out = service.execute(
                "{\"goal\":\"g\",\"mode\":\"subagent\",\"agentRef\":\"bogus\"}", ctx(), List.of());

        JsonNode node = JSON.readTree(out);
        assertEquals(1, node.get("overall").get("failed").asInt());
        verify(subRunner, never()).execute(any(), anyString(), any(), anyList());
    }

    // ---------------- P0/P1/P2/P3 new semantics ----------------

    private String resultOf(String out, String key) throws Exception {
        JsonNode node = JSON.readTree(out);
        for (JsonNode r : node.get("results")) {
            if (key.equals(r.get("key").asText())) {
                JsonNode result = r.get("result");
                return result == null || result.isNull() ? "null" : result.toString();
            }
        }
        throw new IllegalStateException("no such lane: " + key);
    }

    /** Read framework metadata from the aggregate lane node (`meta` object; `result` no longer contains _meta). */
    private JsonNode laneMeta(String out, String key) throws Exception {
        JsonNode node = JSON.readTree(out);
        for (JsonNode r : node.get("results")) {
            if (key.equals(r.get("key").asText())) {
                return r.get("meta");
            }
        }
        throw new IllegalStateException("no such lane: " + key);
    }

    private List<String> dispatchedToolRefs() {
        org.mockito.ArgumentCaptor<RuntimeTool> captor = org.mockito.ArgumentCaptor.forClass(RuntimeTool.class);
        verify(subRunner, org.mockito.Mockito.atLeastOnce()).execute(captor.capture(), anyString(),
                any(SubRunExecutionContext.class), anyList());
        List<String> refs = new java.util.ArrayList<>();
        for (RuntimeTool t : captor.getAllValues()) {
            refs.add(t.getToolRef());
        }
        return refs;
    }

    @Test
    void laneWithoutStatus_markedUncertainAndRetried() throws Exception {
        given(subRunnerProvider.getIfAvailable()).willReturn(subRunner);
        given(subRunner.execute(any(RuntimeTool.class), anyString(), any(SubRunExecutionContext.class), anyList()))
                .willReturn("{\"value\":1}");

        String out = service.execute("""
                {"goal":"dag","mode":"subagent","tasks":[{"key":"a","goal":"ga"}]}""", ctx(), List.of());

        JsonNode meta = laneMeta(out, "a");
        assertEquals("UNCERTAIN", meta.get("status").asText());
        assertEquals("FORMAT_VIOLATION", meta.get("errorCategory").asText());
        // Default maxDagRetries=1 → first attempt + 1 redispatch
        verify(subRunner, times(2)).execute(any(RuntimeTool.class), anyString(),
                any(SubRunExecutionContext.class), anyList());
    }

    @Test
    void depLaneMissingProbes_rejectedWithTargetedCategory() throws Exception {
        given(subRunnerProvider.getIfAvailable()).willReturn(subRunner);
        given(subRunner.execute(any(RuntimeTool.class), anyString(), any(SubRunExecutionContext.class), anyList()))
                .willAnswer(inv -> {
                    RuntimeTool t = inv.getArgument(0);
                    if ("agent:b".equals(t.getToolRef())) {
                        // Dependency exists but the probe is missing: _meta lacks upstream_visible/upstream_raw
                        return "{\"_meta\":{\"contract\":\"delegate-lane/v1\",\"status\":\"OK\",\"verdict\":\"COMPUTED\"}}";
                    }
                    return laneOk("{}", null, false);
                });

        String out = service.execute("""
                {"goal":"dag","mode":"subagent","tasks":[
                  {"key":"a","goal":"ga"},
                  {"key":"b","goal":"gb","dependsOn":["a"]}]}""", ctx(), List.of());

        JsonNode meta = laneMeta(out, "b");
        assertEquals("UNCERTAIN", meta.get("status").asText());
        assertEquals("FORMAT_VIOLATION", meta.get("errorCategory").asText());
        assertTrue(meta.get("reason").asText().contains("upstream_visible"));
    }

    @Test
    void failFast_upstreamMissing_laneNotDispatched() throws Exception {
        given(subRunnerProvider.getIfAvailable()).willReturn(subRunner);
        given(subRunner.execute(any(RuntimeTool.class), anyString(), any(SubRunExecutionContext.class), anyList()))
                .willReturn("I could not compute that");

        String out = service.execute("""
                {"goal":"dag","mode":"subagent","tasks":[
                  {"key":"a","goal":"ga"},
                  {"key":"b","goal":"gb","dependsOn":["a"]}]}""", ctx(), List.of());

        // a failed (first attempt + retry); b was never dispatched, recorded directly as UPSTREAM_MISSING
        List<String> refs = dispatchedToolRefs();
        assertEquals(2, refs.size(), "only a should run (initial + 1 retry)");
        assertTrue(refs.stream().allMatch("agent:a"::equals), "b must never be dispatched");
        JsonNode meta = laneMeta(out, "b");
        assertEquals("UPSTREAM_MISSING", meta.get("verdict").asText());
        assertEquals("UPSTREAM_MISSING", meta.get("errorCategory").asText());
    }

    /** FIX-1 regression: the upstream fails on the first round, retries and then recovers → the downstream must not be stranded and must be dispatched successfully in a later pass. */
    @Test
    void retry_recoversUpstream_downstreamDispatchedInNextPass() throws Exception {
        given(subRunnerProvider.getIfAvailable()).willReturn(subRunner);
        java.util.concurrent.atomic.AtomicInteger aCalls = new java.util.concurrent.atomic.AtomicInteger();
        given(subRunner.execute(any(RuntimeTool.class), anyString(), any(SubRunExecutionContext.class), anyList()))
                .willAnswer(inv -> {
                    RuntimeTool t = inv.getArgument(0);
                    if ("agent:a".equals(t.getToolRef())) {
                        // a: the first round fails on content (triggering a retry), the second attempt succeeds
                        return aCalls.getAndIncrement() == 0
                                ? "I could not compute that"
                                : laneOk("{\"value\":\"a-ok\"}", "COMPUTED", false);
                    }
                    if ("agent:b".equals(t.getToolRef())) {
                        // b depends on a: a is OK at redispatch time, so the dependency probe must be included (upstream_visible/upstream_raw)
                        return laneOk("{\"value\":\"b-ok\"}", "COMPUTED", true);
                    }
                    return "{}";
                });

        String out = service.execute("""
                {"goal":"dag","mode":"subagent","tasks":[
                  {"key":"a","goal":"ga"},
                  {"key":"b","goal":"gb","dependsOn":["a"]}]}""", ctx(), List.of());

        // a succeeds on retry and b is caught up — downstream is no longer stranded by UPSTREAM_MISSING
        JsonNode aMeta = laneMeta(out, "a");
        assertEquals("OK", aMeta.get("status").asText());
        JsonNode bMeta = laneMeta(out, "b");
        assertEquals("OK", bMeta.get("status").asText(), "下游必须在上游恢复后补齐，不得搁浅");
        // Dispatch count: a=2 (first round + retry), b=1 (skipped in the first round, dispatched later)
        List<String> refs = dispatchedToolRefs();
        assertEquals(2, refs.stream().filter("agent:a"::equals).count());
        assertEquals(1, refs.stream().filter("agent:b"::equals).count());
    }

    @Test
    void verdictContradiction_statusOkWithMissingVerdict_flagged() throws Exception {
        given(subRunnerProvider.getIfAvailable()).willReturn(subRunner);
        given(subRunner.execute(any(RuntimeTool.class), anyString(), any(SubRunExecutionContext.class), anyList()))
                .willAnswer(inv -> {
                    RuntimeTool t = inv.getArgument(0);
                    if ("agent:b".equals(t.getToolRef())) {
                        return "{\"_meta\":{\"contract\":\"delegate-lane/v1\",\"status\":\"OK\","
                                + "\"verdict\":\"UPSTREAM_MISSING\",\"upstream_visible\":false,\"upstream_raw\":\"\"}}";
                    }
                    return laneOk("{}", null, false);
                });

        String out = service.execute("""
                {"goal":"dag","mode":"subagent","tasks":[
                  {"key":"a","goal":"ga"},
                  {"key":"b","goal":"gb","dependsOn":["a"]}]}""", ctx(), List.of());

        JsonNode meta = laneMeta(out, "b");
        assertEquals("UNCERTAIN", meta.get("status").asText());
        assertTrue(meta.get("reason").asText().contains("contradicts"));
    }

    @Test
    void countMismatch_expandThenAggregateEnforced() throws Exception {
        given(subRunnerProvider.getIfAvailable()).willReturn(subRunner);
        given(subRunner.execute(any(RuntimeTool.class), anyString(), any(SubRunExecutionContext.class), anyList()))
                .willReturn("{\"chars\":[\"0\",\"1\",\"2\",\"3\",\"4\",\"5\",\"6\",\"7\",\"8\",\"9\",\"a\",\"b\",\"c\",\"d\"],"
                        + "\"count\":13,\"_meta\":{\"contract\":\"delegate-lane/v1\",\"status\":\"OK\",\"verdict\":\"COMPUTED\"}}");

        String out = service.execute("""
                {"goal":"dag","mode":"subagent","tasks":[{"key":"d","goal":"gd"}]}""", ctx(), List.of());

        JsonNode meta = laneMeta(out, "d");
        assertEquals("UNCERTAIN", meta.get("status").asText());
        assertTrue(meta.get("reason").asText().contains("mismatch"));
    }

    @Test
    void chainNonString_flagged() throws Exception {
        given(subRunnerProvider.getIfAvailable()).willReturn(subRunner);
        given(subRunner.execute(any(RuntimeTool.class), anyString(), any(SubRunExecutionContext.class), anyList()))
                .willReturn("{\"chain\":[null],\"_meta\":{\"contract\":\"delegate-lane/v1\",\"status\":\"OK\",\"verdict\":\"COMPUTED\"}}");

        String out = service.execute("""
                {"goal":"dag","mode":"subagent","tasks":[{"key":"c","goal":"gc"}]}""", ctx(), List.of());

        JsonNode meta = laneMeta(out, "c");
        assertEquals("UNCERTAIN", meta.get("status").asText());
        assertTrue(meta.get("reason").asText().contains("chain"));
    }

    @Test
    void envelope_carriesFrameworkProbeFields() throws Exception {        given(subRunnerProvider.getIfAvailable()).willReturn(subRunner);
        given(subRunner.execute(any(RuntimeTool.class), anyString(), any(SubRunExecutionContext.class), anyList()))
                .willAnswer(inv -> {
                    RuntimeTool t = inv.getArgument(0);
                    if ("agent:b".equals(t.getToolRef())) {
                        return laneOk("{}", "COMPUTED", true);
                    }
                    return laneOk("{}", null, false);
                });

        service.execute("""
                {"goal":"dag","mode":"subagent","tasks":[
                  {"key":"a","goal":"ga"},
                  {"key":"b","goal":"gb","dependsOn":["a"]}]}""", ctx(), List.of());

        org.mockito.ArgumentCaptor<RuntimeTool> captor = org.mockito.ArgumentCaptor.forClass(RuntimeTool.class);
        verify(subRunner, org.mockito.Mockito.atLeastOnce()).execute(captor.capture(), anyString(),
                any(SubRunExecutionContext.class), anyList());
        boolean seen = false;
        for (RuntimeTool t : captor.getAllValues()) {
            if ("agent:b".equals(t.getToolRef()) && t.getExecBinding() != null) {
                Object contact = t.getExecBinding().get(
                        com.buukle.agent.runtime.kernel.constants.ExecBindingKeys.DELEGATE_CONTACT);
                if (contact != null && contact.toString().contains("\"upstream_visible\"")
                        && contact.toString().contains("\"upstream_raw\"")) {
                    seen = true;
                    break;
                }
            }
        }
        assertTrue(seen, "b 的信封必须携带框架计算的 upstream_visible/upstream_raw");
    }

    @Test
    void unknownContractVersion_flaggedAsDrift() throws Exception {
        given(subRunnerProvider.getIfAvailable()).willReturn(subRunner);
        given(subRunner.execute(any(RuntimeTool.class), anyString(), any(SubRunExecutionContext.class), anyList()))
                .willReturn("{\"_meta\":{\"contract\":\"bogus/v9\",\"status\":\"OK\",\"verdict\":\"COMPUTED\"}}");

        String out = service.execute("""
                {"goal":"dag","mode":"subagent","tasks":[{"key":"a","goal":"ga"}]}""", ctx(), List.of());

        JsonNode meta = laneMeta(out, "a");
        assertEquals("UNCERTAIN", meta.get("status").asText());
        assertTrue(meta.get("reason").asText().contains("contract"));
    }

    @Test
    void aggregateNode_exposesMetaAndFenced() throws Exception {
        given(subRunnerProvider.getIfAvailable()).willReturn(subRunner);
        given(subRunner.execute(any(RuntimeTool.class), anyString(), any(SubRunExecutionContext.class), anyList()))
                .willReturn(laneOk("{\"value\":1}", "COMPUTED", false));

        String out = service.execute("""
                {"goal":"dag","mode":"subagent","tasks":[{"key":"a","goal":"ga"}]}""", ctx(), List.of());

        JsonNode node = JSON.readTree(out);
        JsonNode lane = node.get("results").get(0);
        assertEquals("a", lane.get("key").asText());
        assertEquals("OK", lane.get("status").asText());
        assertTrue(lane.get("meta").isObject(), "meta 应为完整对象而非 present/absent 字符串");
        assertEquals("OK", lane.get("meta").get("status").asText());
        assertTrue(lane.has("fenced"));
        assertTrue(lane.has("result"));
    }

    @Test
    void aggregateNode_marksFencedOutput() throws Exception {
        given(subRunnerProvider.getIfAvailable()).willReturn(subRunner);
        given(subRunner.execute(any(RuntimeTool.class), anyString(), any(SubRunExecutionContext.class), anyList()))
                .willReturn("```json\n" + laneOk("{\"value\":1}", "COMPUTED", false) + "\n```");

        String out = service.execute("""
                {"goal":"dag","mode":"subagent","tasks":[{"key":"a","goal":"ga"}]}""", ctx(), List.of());

        JsonNode lane = JSON.readTree(out).get("results").get(0);
        assertEquals("OK", lane.get("status").asText());
        assertTrue(lane.get("fenced").asBoolean(), "围栏输出应被标记，供 harness 断言");
    }

    @Test
    void frameworkWrapper_carriesKeyAndRaw() throws Exception {
        given(subRunnerProvider.getIfAvailable()).willReturn(subRunner);
        given(subRunner.execute(any(RuntimeTool.class), anyString(), any(SubRunExecutionContext.class), anyList()))
                .willReturn("I could not compute that");

        String out = service.execute("""
                {"goal":"dag","mode":"subagent","tasks":[
                  {"key":"a","goal":"ga"},
                  {"key":"b","goal":"gb","dependsOn":["a"]}]}""", ctx(), List.of());

        // b was not dispatched: the failure envelope has a key at the top level (for reconciliation) and a category in _meta (identifiable as platform-issued)
        JsonNode lane = null;
        for (JsonNode r : JSON.readTree(out).get("results")) {
            if ("b".equals(r.get("key").asText())) {
                lane = r;
            }
        }
        assertTrue(lane != null, "aggregate 必须含 b lane");
        assertEquals("b", lane.get("key").asText());
        assertTrue(lane.get("meta").isObject());
        assertEquals("UPSTREAM_MISSING", lane.get("meta").get("errorCategory").asText());
    }
}
