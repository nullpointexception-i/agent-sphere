package com.buukle.agent.agui.dtvo;

import lombok.Data;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * AG-UI {@code RunAgentInput}（@ag-ui/client HttpAgent POST 到 /services/chat/run 的请求体）。
 * 只解析本后端需要的字段，未知字段按 AG-UI 协议可忽略。
 */
@Data
public class AguiRunInputVO implements Serializable {
    private String threadId;
    private String runId;
    private List<AguiMessageVO> messages = new ArrayList<>();
    private Map<String, Object> state = new LinkedHashMap<>();
    private List<String> tools = new ArrayList<>();
    private List<Map<String, Object>> context = new ArrayList<>();
    private Map<String, Object> forwardedProps = new LinkedHashMap<>();
}
