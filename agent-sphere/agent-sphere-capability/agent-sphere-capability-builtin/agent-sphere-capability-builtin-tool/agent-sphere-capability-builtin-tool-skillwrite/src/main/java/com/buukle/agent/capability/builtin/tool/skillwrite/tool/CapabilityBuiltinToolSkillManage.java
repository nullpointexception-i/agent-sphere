package com.buukle.agent.capability.builtin.tool.skillwrite.tool;

import com.buukle.agent.capability.builtin.dtvo.enums.BuiltinToolEnum;
import com.buukle.agent.capability.builtin.tool.skillwrite.dtvo.dto.SkillManageExecuteContext;
import com.buukle.agent.capability.builtin.tool.skillwrite.dtvo.enums.SkillManageAction;
import com.buukle.agent.capability.builtin.tool.skillwrite.dtvo.vo.SkillManageResultVO;
import com.buukle.agent.capability.builtin.tool.spi.CapabilityBuiltinToolSpi;
import com.buukle.agent.capability.builtin.tool.spi.constant.BuiltinToolConstants;
import com.buukle.agent.capability.builtin.tool.spi.dtvo.ExecuteContext;
import com.buukle.agent.capability.builtin.tool.spi.dtvo.ExecuteResult;
import com.buukle.agent.capability.builtin.tool.spi.dtvo.ToolInfoVO;
import com.buukle.agent.capability.builtin.tool.spi.dtvo.vo.HeadingInfo;
import com.buukle.agent.capability.builtin.tool.spi.util.MarkdownParser;
import com.buukle.agent.capability.builtin.tool.spi.util.ToolSchemaUtil;
import com.buukle.agent.capability.skill.dtvo.dto.CreateSkillDTO;
import com.buukle.agent.capability.skill.dtvo.vo.SkillVO;
import com.buukle.agent.capability.skill.spi.CapabilitySkillSpi;
import com.buukle.agent.common.skill.SkillTemplateValidator;
import com.buukle.agent.instance.dtvo.dto.CreateInstanceCapabilityDTO;
import com.buukle.agent.instance.dtvo.vo.CapabilityVO;
import com.buukle.agent.instance.dtvo.vo.SessionVO;
import com.buukle.agent.instance.spi.InstanceCapabilitySpi;
import com.buukle.agent.instance.spi.SessionSpi;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

import static com.buukle.agent.instance.dtvo.enums.InstanceCapabilityEnum.CAPABILITY_TYPE_SKILL;
import static com.buukle.agent.instance.dtvo.enums.InstanceCapabilityEnum.STATUS_ENABLED;

@Slf4j
@Component
@RequiredArgsConstructor
public class CapabilityBuiltinToolSkillManage implements CapabilityBuiltinToolSpi {

    static final String OP_REPLACE_SECTION = "replace_section";
    static final String OP_INSERT_AFTER = "insert_after";
    static final String OP_REPLACE_TEXT = "replace_text";

    static final int PREVIEW_LENGTH = 200;
    static final String MARKDOWN_FENCE = "```json";

    private static final String TOOL_DESCRIPTION = """
            创建/追加/更新绑定到当前实例的技能。
            仅支持 action = create / append / update。
            查询/列出/读取技能请使用 builtin_8（技能库），不要对本工具传 list。

            definition 为 JSON：{"version":1,"parameters":{JSON Schema},"promptTemplate":"<Markdown>"}。
            promptTemplate 必须遵循固定的标题结构；保存前会**前置校验**（UI 表单与 agent 工具共用同一校验），不合规直接报错并回带模板：
            - `# <技能名>`：有且仅有一个 H1；
            - `## stepN <步骤名>`：步骤，至少一个（N 为数字，如 step1）；
            - `### stepN.M <子步骤名>`：子步骤，必须挂在其上方最近的、同编号的 `## stepN` 之下；
            - 非步骤的 H2（如 概述 / 收尾对账）不要带 `stepN` 前缀。

            标准模板：
            """ + SkillTemplateValidator.SKILL_TEMPLATE;

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final CapabilitySkillSpi skillSpi;
    private final InstanceCapabilitySpi instanceCapabilitySpi;
    private final SessionSpi sessionSpi;

    @Override
    public BuiltinToolEnum getToolType() {
        return BuiltinToolEnum.SKILL_MANAGE;
    }

    @Override
    public boolean needConfig() {
        return false;
    }

    @Override
    public ToolInfoVO getInfo() {
        ToolInfoVO info = new ToolInfoVO();
        info.setName(BuiltinToolConstants.NAME_PREFIX + CapabilityBuiltinToolSkillManage.class.getSimpleName());
        info.setDescription(TOOL_DESCRIPTION);
        info.setDisplayNameCn("技能管理");
        info.setDisplayNameEn("Skill Manage");
        info.setParamSchema(ToolSchemaUtil.generateParamSchema(SkillManageExecuteContext.class));
        info.setResponseSchema(ToolSchemaUtil.generateParamSchema(SkillManageResultVO.class));
        return info;
    }

    @Override
    public Class<? extends ExecuteContext> getContextType() {
        return SkillManageExecuteContext.class;
    }

    @Override
    public Class<? extends ExecuteResult> getResultType() {
        return SkillManageResultVO.class;
    }

    @Override
    public ExecuteResult execute(ExecuteContext ctx) {
        try {
            SkillManageExecuteContext swCtx = (SkillManageExecuteContext) ctx;
            String action = swCtx.getAction();
            if (action == null || action.isBlank()) {
                SkillManageResultVO r = new SkillManageResultVO();
                r.setAction("unknown");
                r.setPreview("action is required. Allowed: create/append/update. "
                        + "To list/search/get skills, use builtin_8 (skill library).");
                return r;
            }
            SkillManageAction act = SkillManageAction.from(action);
            if (act == null) {
                log.warn("Unknown skill manage action: {}", action);
                return error(action, "Unsupported action: " + action
                        + ". Allowed: create/append/update. "
                        + "To list/count/search/get skills, use builtin_8 (skill library).");
            }
            Long skillId = swCtx.getSkillId();
            return switch (act) {
                case CREATE -> handleCreate(swCtx);
                case APPEND -> handleAppend(swCtx, skillId);
                case UPDATE -> handleUpdate(swCtx, skillId);
            };
        } catch (Exception e) {
            log.warn("skill manage execute failed action={}", ctx instanceof SkillManageExecuteContext s ? s.getAction() : "unknown", e);
            SkillManageResultVO r = new SkillManageResultVO();
            r.setAction("error");
            r.setPreview("Internal error: " + e.getMessage());
            return r;
        }
    }

    private SkillManageResultVO handleCreate(SkillManageExecuteContext ctx) {
        String name = ctx.getName();
        String definition = ctx.getDefinition();
        if (name == null || name.isBlank()) {
            return error(SkillManageAction.CREATE.value(), "name is required");
        }
        if (definition == null || definition.isBlank()) {
            return error(SkillManageAction.CREATE.value(), "definition is required (JSON with parameters + promptTemplate)");
        }
        String validation = validateDefinition(definition);
        if (validation != null) {
            return error(SkillManageAction.CREATE.value(), validation);
        }
        SessionContext session = resolveSession(ctx.getSessionId());
        if (session == null || session.instanceId() == null || session.instanceId() <= 0) {
            return error(SkillManageAction.CREATE.value(), "sessionId is required and must resolve to an agent instance");
        }

        CreateSkillDTO dto = new CreateSkillDTO();
        dto.setName(name.trim());
        dto.setDescription(ctx.getDescription());
        dto.setDefinition(definition.trim());
        SkillVO created = skillSpi.createSkill(dto, session.createdBy());

        CreateInstanceCapabilityDTO cap = new CreateInstanceCapabilityDTO();
        cap.setInstanceId(session.instanceId());
        cap.setCapabilityType(CAPABILITY_TYPE_SKILL);
        cap.setCapabilityId(created.getId());
        cap.setStatus(STATUS_ENABLED);
        instanceCapabilitySpi.createCapability(cap);

        SkillManageResultVO r = new SkillManageResultVO();
        r.setAction(SkillManageAction.CREATE.value());
        r.setSkillId(created.getId());
        r.setName(created.getName());
        r.setDescription(created.getDescription());
        r.setPreview(previewOf(definition));
        return r;
    }

    private SkillManageResultVO handleAppend(SkillManageExecuteContext ctx, Long skillId) {
        if (skillId == null) {
            return error(SkillManageAction.APPEND.value(), "skillId is required for append");
        }
        if (ctx.getContent() == null || ctx.getContent().isBlank()) {
            return error(SkillManageAction.APPEND.value(), "content is required for append");
        }
        SessionContext session = resolveSession(ctx.getSessionId());
        if (session == null || session.instanceId() == null || session.instanceId() <= 0) {
            return error(SkillManageAction.APPEND.value(), "sessionId is required and must resolve to an agent instance");
        }
        if (!isSkillBound(session.instanceId(), skillId)) {
            return error(SkillManageAction.APPEND.value(), "Skill " + skillId + " is not bound to the current agent instance");
        }
        SkillVO existing = skillSpi.getSkill(skillId);
        if (existing == null) {
            return error(SkillManageAction.APPEND.value(), "Skill not found: " + skillId);
        }
        String definition = existing.getDefinition();
        String prompt = extractPromptTemplate(definition);
        if (prompt == null) {
            return error(SkillManageAction.APPEND.value(), "Skill has no textual 'promptTemplate' to append to");
        }
        JsonNode root;
        try {
            root = readDefinitionJson(definition);
        } catch (Exception e) {
            return error(SkillManageAction.APPEND.value(), "Invalid definition JSON: " + e.getMessage());
        }
        if (root == null || !root.isObject()) {
            return error(SkillManageAction.APPEND.value(), "definition must be a JSON object");
        }
        ObjectNode rebuilt = root.deepCopy();
        String appended = prompt.endsWith("\n") ? prompt + ctx.getContent().trim() : prompt + "\n\n" + ctx.getContent().trim();
        rebuilt.put("promptTemplate", appended);
        // 追加后同样要合规：否则 append 会成为绕过结构校验的后门。
        String appendValidation = validateDefinition(rebuilt.toString());
        if (appendValidation != null) {
            return error(SkillManageAction.APPEND.value(), appendValidation);
        }
        CreateSkillDTO dto = new CreateSkillDTO();
        dto.setName(existing.getName());
        dto.setDescription(existing.getDescription());
        dto.setDefinition(rebuilt.toString());
        SkillVO updated = skillSpi.updateSkill(skillId, dto, session.createdBy());
        SkillManageResultVO r = new SkillManageResultVO();
        r.setAction(SkillManageAction.APPEND.value());
        r.setSkillId(updated.getId());
        r.setName(updated.getName());
        r.setDescription(updated.getDescription());
        r.setPreview(truncate(appended));
        return r;
    }

    private SkillManageResultVO handleUpdate(SkillManageExecuteContext ctx, Long skillId) {
        if (skillId == null) {
            return error(SkillManageAction.UPDATE.value(), "skillId is required");
        }
        SessionContext session = resolveSession(ctx.getSessionId());
        if (session == null || session.instanceId() == null || session.instanceId() <= 0) {
            return error(SkillManageAction.UPDATE.value(), "sessionId is required and must resolve to an agent instance");
        }
        if (!isSkillBound(session.instanceId(), skillId)) {
            return error(SkillManageAction.UPDATE.value(), "Skill " + skillId + " is not bound to the current agent instance");
        }
        SkillVO existing = skillSpi.getSkill(skillId);
        if (existing == null) {
            return error(SkillManageAction.UPDATE.value(), "Skill not found: " + skillId);
        }

        CreateSkillDTO dto = new CreateSkillDTO();
        dto.setName(ctx.getName() != null && !ctx.getName().isBlank() ? ctx.getName().trim() : existing.getName());
        dto.setDescription(ctx.getDescription() != null ? ctx.getDescription() : existing.getDescription());

        String operation = ctx.getOperation();
        if (operation == null || operation.isBlank()) {
            if (ctx.getDefinition() != null) {
                dto.setDefinition(ctx.getDefinition().trim());
                String validation = validateDefinition(dto.getDefinition());
                if (validation != null) {
                    return error(SkillManageAction.UPDATE.value(), validation);
                }
            }
        } else {
            PatchedDefinition patched = applyMarkdownPatch(existing.getDefinition(), ctx);
            if (patched.error() != null) {
                SkillManageResultVO r = error(SkillManageAction.UPDATE.value(), patched.error());
                r.setHeadings(patched.headings());
                r.setTotalLines(patched.totalLines());
                return r;
            }
            dto.setDefinition(patched.definition());
            String validation = validateDefinition(dto.getDefinition());
            if (validation != null) {
                return error(SkillManageAction.UPDATE.value(), validation);
            }
        }

        SkillVO updated = skillSpi.updateSkill(skillId, dto, session.createdBy());
        SkillManageResultVO r = new SkillManageResultVO();
        r.setAction(SkillManageAction.UPDATE.value());
        r.setSkillId(updated.getId());
        r.setName(updated.getName());
        r.setDescription(updated.getDescription());
        r.setPreview(previewOf(updated.getDefinition()));
        return r;
    }

    /** 复用 doc 模块的 Markdown 工具编辑 skill definition 内嵌的 promptTemplate，保留其它未知键。 */
    private PatchedDefinition applyMarkdownPatch(String definition, SkillManageExecuteContext ctx) {
        if (definition == null || definition.isBlank()) {
            return PatchedDefinition.error("definition is empty; cannot patch");
        }
        String prompt = extractPromptTemplate(definition);
        if (prompt == null) {
            return PatchedDefinition.error("definition has no textual 'promptTemplate' to patch");
        }
        String headOrText;
        List<HeadingInfo> headings = null;
        switch (ctx.getOperation()) {
            case OP_REPLACE_SECTION -> {
                if (blank(ctx.getHeadingSearch())) {
                    return PatchedDefinition.error("headingSearch is required for replace_section");
                }
                String section = MarkdownParser.extractSection(prompt, ctx.getHeadingSearch());
                if (section == null) {
                    headings = MarkdownParser.parseHeadings(prompt);
                    return PatchedDefinition.error("Section not found: \"" + ctx.getHeadingSearch() + "\". Available headings:")
                            .withHeadings(headings, lineCount(prompt));
                }
                String replaced = MarkdownParser.replaceSection(prompt, section, ctx.getContent() == null ? "" : ctx.getContent());
                if (replaced == null) {
                    return PatchedDefinition.error("Section found but replacement failed. Use get(skillId=...) to verify the promptTemplate first.");
                }
                headOrText = replaced;
            }
            case OP_INSERT_AFTER -> {
                if (blank(ctx.getHeadingSearch())) {
                    return PatchedDefinition.error("headingSearch is required for insert_after");
                }
                String inserted = MarkdownParser.insertAfterSection(prompt, ctx.getHeadingSearch(), ctx.getContent());
                if (inserted == null) {
                    headings = MarkdownParser.parseHeadings(prompt);
                    return PatchedDefinition.error("Heading not found: \"" + ctx.getHeadingSearch() + "\". Available headings:")
                            .withHeadings(headings, lineCount(prompt));
                }
                headOrText = inserted;
            }
            case OP_REPLACE_TEXT -> {
                if (blank(ctx.getSearchText()) || ctx.getReplaceText() == null) {
                    return PatchedDefinition.error("searchText and replaceText are required for replace_text");
                }
                String replaced = MarkdownParser.replaceFirst(prompt, ctx.getSearchText(), ctx.getReplaceText());
                if (replaced == null) {
                    return PatchedDefinition.error("Text not found or found multiple times: \"" + ctx.getSearchText() + "\". Use get(skillId=...) to verify.");
                }
                headOrText = replaced;
            }
            default -> {
                return PatchedDefinition.error("Unknown operation: " + ctx.getOperation()
                        + ". Supported: replace_section, insert_after, replace_text");
            }
        }
        JsonNode root;
        try {
            root = readDefinitionJson(definition);
        } catch (Exception e) {
            return PatchedDefinition.error("Invalid definition JSON: " + e.getMessage());
        }
        if (root == null || !root.isObject()) {
            return PatchedDefinition.error("definition must be a JSON object");
        }
        ObjectNode rebuilt = (ObjectNode) root.deepCopy();
        rebuilt.put("promptTemplate", headOrText);
        return PatchedDefinition.ok(rebuilt.toString());
    }

    private boolean isSkillBound(Long instanceId, Long skillId) {
        for (CapabilityVO cap : instanceCapabilitySpi.getCapabilitiesByInstance(instanceId)) {
            if (CAPABILITY_TYPE_SKILL.equals(cap.getCapabilityType())
                    && cap.getCapabilityId() != null
                    && cap.getCapabilityId().equals(skillId)) {
                return true;
            }
        }
        return false;
    }

    /** 校验 definition：JSON 形状 + promptTemplate 结构；委托 common 的共用校验（UI 表单/工具口径一致）。 */
    private String validateDefinition(String definition) {
        return SkillTemplateValidator.validate(definition);
    }

    private JsonNode readDefinitionJson(String definition) throws Exception {
        String jsonStr = definition;
        int fenceStart = definition.indexOf(MARKDOWN_FENCE);
        if (fenceStart >= 0) {
            int contentStart = fenceStart + MARKDOWN_FENCE.length();
            int fenceEnd = definition.indexOf("```", contentStart);
            jsonStr = fenceEnd >= 0 ? definition.substring(contentStart, fenceEnd).trim() : definition;
        }
        return OBJECT_MAPPER.readTree(jsonStr);
    }

    private SessionContext resolveSession(Long sessionId) {
        if (sessionId == null) return null;
        try {
            SessionVO session = sessionSpi.getSession(sessionId);
            if (session == null) return null;
            return new SessionContext(session.getAgentInstanceId(), session.getCreatedBy());
        } catch (Exception e) {
            log.warn("Failed to resolve session {} for skill manage", sessionId, e);
            return null;
        }
    }

    private String previewOf(String definition) {
        if (definition == null) return "";
        String normalized = definition.replaceAll("[#*`\\n\\r]+", " ").trim();
        return normalized.length() > PREVIEW_LENGTH ? normalized.substring(0, PREVIEW_LENGTH) + "…" : normalized;
    }

    private static String truncate(String text) {
        if (text == null) return "";
        return text.length() > PREVIEW_LENGTH ? text.substring(0, PREVIEW_LENGTH) + "..." : text;
    }

    /** 解出 definition 内嵌的 promptTemplate 文本；非对象 JSON / 无文本 promptTemplate 时返回 null。 */
    private String extractPromptTemplate(String definition) {
        if (definition == null || definition.isBlank()) return null;
        try {
            JsonNode root = readDefinitionJson(definition);
            if (root == null || !root.isObject()) return null;
            JsonNode promptNode = root.get("promptTemplate");
            return promptNode != null && promptNode.isTextual() ? promptNode.asText() : null;
        } catch (Exception e) {
            return null;
        }
    }

    private SkillManageResultVO error(String action, String message) {
        SkillManageResultVO r = new SkillManageResultVO();
        r.setAction(action);
        r.setPreview(message);
        return r;
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    private static int lineCount(String s) {
        return s == null ? 0 : s.split("\n", -1).length;
    }

    private record SessionContext(Long instanceId, String createdBy) {
    }

    private static final class PatchedDefinition {
        private final String definition;
        private final String error;
        private final List<HeadingInfo> headings;
        private final Integer totalLines;

        private PatchedDefinition(String definition, String error, List<HeadingInfo> headings, Integer totalLines) {
            this.definition = definition;
            this.error = error;
            this.headings = headings;
            this.totalLines = totalLines;
        }

        static PatchedDefinition ok(String definition) {
            return new PatchedDefinition(definition, null, null, null);
        }

        static PatchedDefinition error(String error) {
            return new PatchedDefinition(null, error, null, null);
        }

        PatchedDefinition withHeadings(List<HeadingInfo> headings, int totalLines) {
            return new PatchedDefinition(null, error, headings, totalLines);
        }

        String definition() {
            return definition;
        }

        String error() {
            return error;
        }

        List<HeadingInfo> headings() {
            return headings;
        }

        Integer totalLines() {
            return totalLines;
        }
    }
}
