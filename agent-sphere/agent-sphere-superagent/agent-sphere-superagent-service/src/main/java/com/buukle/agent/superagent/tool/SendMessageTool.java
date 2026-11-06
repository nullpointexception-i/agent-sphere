package com.buukle.agent.superagent.tool;

import com.buukle.agent.capability.builtin.dtvo.enums.BuiltinToolEnum;
import com.buukle.agent.capability.builtin.tool.spi.constant.BuiltinToolConstants;
import com.buukle.agent.capability.builtin.tool.spi.dtvo.ExecuteContext;
import com.buukle.agent.capability.builtin.tool.spi.dtvo.ExecuteResult;
import org.springframework.stereotype.Component;

@Component
public class SendMessageTool extends AbstractAgentRouterTool {

    private static final String SCHEMA = """
            {"type":"object","properties":{
              "text":{"type":"string","description":"Reply text, at most 100 words"},
              "format":{"type":"string","enum":["TEXT","CARD"],"description":"Message format"},
              "cardType":{"type":"string","description":"Card type when format=CARD"},
              "cardTitle":{"type":"string","description":"Card title when format=CARD"},
              "cardStatus":{"type":"string","description":"Card status when format=CARD"},
              "cardSummary":{"type":"string","description":"Card summary when format=CARD"},
              "workItemId":{"type":"integer","description":"Related work item id when format=CARD"}
            },"required":["text"]}
            """;

    public SendMessageTool(AgentRouterToolService toolService) {
        super(toolService);
    }

    @Override
    public BuiltinToolEnum getToolType() {
        return BuiltinToolEnum.SEND_MESSAGE;
    }

    @Override
    public ExecuteResult execute(ExecuteContext ctx) {
        AgentRouterToolContext input = context(ctx);
        return toolService.sendMessage(input.getSessionId(), input.getRunId(), input.getCallId(),
                input.getText(), input.getFormat(), input.getCardType(), input.getCardTitle(),
                input.getCardStatus(), input.getCardSummary(), input.getWorkItemId());
    }

    @Override
    protected String toolName() {
        return BuiltinToolConstants.NAME_PREFIX + "SendMessageTool";
    }

    @Override
    protected String description() {
        return "Only for task cards and proactive notifications (format=CARD, or a work-item update). "
                + "For a normal answer, put the final reply in your own message text and do NOT call this tool: "
                + "the system delivers that text automatically.";
    }

    @Override
    protected String displayNameCn() {
        return "发送消息";
    }

    @Override
    protected String displayNameEn() {
        return "Send Message";
    }

    @Override
    protected String parameterSchema() {
        return SCHEMA;
    }
}
