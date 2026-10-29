package com.buukle.agent.runtime.kernel.service;

import com.buukle.agent.common.constant.ToolApprovalDecision;
import com.buukle.agent.common.constant.ToolApprovalScope;
import com.buukle.agent.instance.dtvo.vo.ToolApprovalVO;
import com.buukle.agent.instance.spi.ToolApprovalGrantSpi;
import com.buukle.agent.instance.spi.ToolApprovalSpi;
import com.buukle.agent.runtime.kernel.contract.TurnToolCall;
import com.buukle.agent.runtime.kernel.port.vo.RuntimeEventDataVO;
import com.buukle.agent.runtime.kernel.port.vo.RuntimeEventVO;
import com.buukle.agent.runtime.kernel.port.vo.RuntimeTool;
import com.buukle.agent.runtime.kernel.port.vo.ToolApprovalStatus;
import com.buukle.agent.util.json.JsonUtils;
import com.fasterxml.jackson.core.type.TypeReference;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * L2 工具审批：底部抽屉 + 多工具 Tabs。
 *
 * <p>审批不再阻塞 SessionRunner 线程：创建审批记录后 run 进入 AWAITING_USER 并退出循环，
 * 用户提交后由 RuntimeOrchestrator 重新唤醒 run，SessionRunner 直接执行已批准的调用。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ToolApprovalService {

    private final ToolApprovalSpi approvalSpi;
    private final ToolApprovalGrantSpi grantSpi;
    private final ApplicationEventPublisher eventPublisher;

    public record ApprovalCall(TurnToolCall call, RuntimeTool tool, boolean pending) {
        public ApprovalCall(TurnToolCall call, RuntimeTool tool) {
            this(call, tool, false);
        }
    }

    /** Creates a pending approval batch and returns its id. */
    public String createPending(Long sessionId, Long runId, List<ApprovalCall> calls) {
        if (calls == null || calls.isEmpty()) {
            return null;
        }
        String approvalId = UUID.randomUUID().toString().substring(0, 12);
        String callsJson = callsJson(calls);
        approvalSpi.createPending(approvalId, sessionId, runId, callsJson);
        eventPublisher.publishEvent(new RuntimeEventVO(
                ToolApprovalStatus.TOOL_APPROVAL_PENDING,
                new RuntimeEventDataVO()
                        .setSessionId(sessionId)
                        .setRunId(runId)
                        .setApprovalId(approvalId)
                        .setToolCallsJson(callsJson)
                        .setPrompt("需要确认工具调用")));
        return approvalId;
    }

    /** Resolve from the bottom drawer. decisionsJson = [{callId, decision}]. */
    public boolean resolve(Long sessionId, Long runId, String approvalId, String decisionsJson) {
        ToolApprovalVO approval = approvalSpi.getByApprovalId(approvalId);
        if (approval == null || !"PENDING".equals(approval.getStatus())) {
            return false;
        }
        List<Map<String, Object>> rows = parseRows(approval.getToolCallsJson());
        Map<String, ToolApprovalDecision> decisions = parseDecisions(rows, decisionsJson);
        if (decisions == null) {
            return false;
        }
        approvalSpi.resolve(approvalId, decisionsJson, "APPROVED", "user");
        for (Map<String, Object> row : rows) {
            if (!pending(row)) {
                continue;
            }
            String callId = String.valueOf(row.getOrDefault("callId", ""));
            ToolApprovalDecision d = decisions.getOrDefault(callId, ToolApprovalDecision.DENY);
            String toolRef = String.valueOf(row.getOrDefault("toolRef", ""));
            if (d == ToolApprovalDecision.DENY) {
                grantSpi.deny(sessionId, runId, toolRef, "user");
            } else if (d == ToolApprovalDecision.ALLOW_SESSION) {
                grantSpi.grant(sessionId, runId, toolRef, ToolApprovalScope.SESSION.name(), "user");
            } else if (d == ToolApprovalDecision.ALLOW_RUN) {
                grantSpi.grant(sessionId, runId, toolRef, ToolApprovalScope.RUN.name(), "user");
            }
        }
        eventPublisher.publishEvent(new RuntimeEventVO(
                ToolApprovalStatus.TOOL_APPROVAL_RESPONDED,
                new RuntimeEventDataVO()
                        .setSessionId(sessionId)
                        .setRunId(runId)
                        .setApprovalId(approvalId)));
        return true;
    }

    public ToolApprovalVO getPending(Long sessionId) {
        return approvalSpi.getPending(sessionId);
    }

    public ToolApprovalVO getByApprovalId(String approvalId) {
        return approvalSpi.getByApprovalId(approvalId);
    }

    public ToolApprovalVO findResumable(Long runId) {
        return approvalSpi.findResumableByRunId(runId);
    }

    public List<ApprovalCall> calls(ToolApprovalVO approval, List<RuntimeTool> tools) {
        List<ApprovalCall> out = new ArrayList<>();
        for (Map<String, Object> row : parseRows(approval.getToolCallsJson())) {
            String callId = String.valueOf(row.getOrDefault("callId", ""));
            String toolName = String.valueOf(row.getOrDefault("toolName", ""));
            RuntimeTool tool = tools.stream()
                    .filter(t -> toolName.equals(t.getLlmToolName()))
                    .findFirst().orElse(null);
            if (tool == null) {
                continue;
            }
            out.add(new ApprovalCall(
                    new TurnToolCall(callId, toolName,
                            String.valueOf(row.getOrDefault("arguments", "{}"))),
                    tool, pending(row)));
        }
        return out;
    }

    public Map<String, ToolApprovalDecision> decisions(ToolApprovalVO approval) {
        Map<String, ToolApprovalDecision> out = new HashMap<>();
        if (approval == null || approval.getDecisionsJson() == null) {
            return out;
        }
        try {
            List<Map<String, Object>> rows = JsonUtils.parse(approval.getDecisionsJson(),
                    new TypeReference<List<Map<String, Object>>>() {
                    });
            for (Map<String, Object> row : rows) {
                out.put(String.valueOf(row.getOrDefault("callId", "")),
                        ToolApprovalDecision.from(String.valueOf(row.getOrDefault("decision", "DENY"))));
            }
        } catch (Exception e) {
            log.warn("Failed to parse tool approval decisions: {}", e.getMessage());
        }
        return out;
    }

    public void consume(String approvalId) {
        approvalSpi.consume(approvalId);
    }

    public void cancelByRunId(Long runId) {
        if (runId == null) return;
        List<ToolApprovalVO> pending = approvalSpi.listPendingByRunId(runId);
        for (ToolApprovalVO approval : pending) {
            approvalSpi.resolve(approval.getApprovalId(), null, "DISMISSED", "system");
            publishDismissed(approval.getSessionId(), runId, approval.getApprovalId());
        }
        approvalSpi.dismissByRunId(runId, "DISMISSED");
    }

    private static Map<String, ToolApprovalDecision> parseDecisions(
            List<Map<String, Object>> rows, String json) {
        if (json == null || json.isBlank()) return null;
        try {
            List<Map<String, Object>> decisionRows = JsonUtils.parse(json,
                    new TypeReference<List<Map<String, Object>>>() {
                    });
            Map<String, ToolApprovalDecision> out = new HashMap<>();
            for (Map<String, Object> row : decisionRows) {
                String callId = String.valueOf(row.getOrDefault("callId", ""));
                ToolApprovalDecision d = ToolApprovalDecision.from(
                        String.valueOf(row.getOrDefault("decision", "DENY")));
                Map<String, Object> call = rows.stream()
                        .filter(r -> pending(r)
                                && callId.equals(String.valueOf(r.getOrDefault("callId", ""))))
                        .findFirst().orElse(null);
                if (call == null) return null;
                if (!d.allowedFor(ToolApprovalScope.from(
                        String.valueOf(call.getOrDefault("approvalScope", "ONCE"))))) {
                    return null;
                }
                out.put(callId, d);
            }
            for (Map<String, Object> row : rows) {
                if (pending(row)
                        && !out.containsKey(String.valueOf(row.getOrDefault("callId", "")))) {
                    return null;
                }
            }
            return out;
        } catch (Exception e) {
            return null;
        }
    }

    private static List<Map<String, Object>> parseRows(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            return JsonUtils.parse(json, new TypeReference<List<Map<String, Object>>>() {
            });
        } catch (Exception e) {
            return List.of();
        }
    }

    private static boolean pending(Map<String, Object> row) {
        Object value = row.get("pending");
        return value == null || Boolean.parseBoolean(String.valueOf(value));
    }

    private void publishDismissed(Long sessionId, Long runId, String approvalId) {
        eventPublisher.publishEvent(new RuntimeEventVO(
                ToolApprovalStatus.TOOL_APPROVAL_DISMISSED,
                new RuntimeEventDataVO()
                        .setSessionId(sessionId)
                        .setRunId(runId)
                        .setApprovalId(approvalId)));
    }

    private static String callsJson(List<ApprovalCall> calls) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (ApprovalCall c : calls) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("callId", c.call().id());
            row.put("toolRef", c.tool().getToolRef());
            row.put("toolName", c.tool().getLlmToolName());
            row.put("displayName", c.tool().getDisplayName());
            row.put("action", action(c.call().arguments()));
            row.put("arguments", c.call().arguments());
            row.put("approvalScope", ToolApprovalScope.from(c.tool().getApprovalScope()).name());
            row.put("pending", c.pending());
            rows.add(row);
        }
        return JsonUtils.toJson(rows);
    }

    private static String action(String args) {
        try {
            return JsonUtils.getMapper().readTree(args == null ? "{}" : args).path("action").asText("");
        } catch (Exception e) {
            return "";
        }
    }
}
