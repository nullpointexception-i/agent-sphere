package com.buukle.agent.capability.builtin.tool.skillwrite.tool;

import com.buukle.agent.capability.builtin.dtvo.enums.BuiltinToolEnum;
import com.buukle.agent.capability.builtin.tool.skillwrite.dtvo.dto.SkillWriteExecuteContext;
import com.buukle.agent.capability.builtin.tool.skillwrite.dtvo.vo.SkillWriteResultVO;
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
import com.buukle.agent.common.skill.SkillDefinitionParser;
import com.buukle.agent.common.sub.agent.InvalidSubRunDefinition;
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

import java.util.ArrayList;
import java.util.List;

import static com.buukle.agent.instance.dtvo.enums.InstanceCapabilityEnum.CAPABILITY_TYPE_SKILL;
import static com.buukle.agent.instance.dtvo.enums.InstanceCapabilityEnum.STATUS_ENABLED;

@Slf4j
@Component
@RequiredArgsConstructor
public class CapabilityBuiltinToolSkillOperation implements CapabilityBuiltinToolSpi {

    static final String ACTION_CREATE = "create";
    static final String ACTION_LIST = "list";
    static final String ACTION_GET = "get";
    static final String ACTION_UPDATE = "update";

    static final String OP_REPLACE_SECTION = "replace_section";
    static final String OP_INSERT_AFTER = "insert_after";
    static final String OP_REPLACE_TEXT = "replace_text";

    static final int PREVIEW_LENGTH = 200;
    static final String MARKDOWN_FENCE = "```json";

    private static final String TOOL_DESCRIPTION = """
            Create, list, read, or update skills bound to the current agent instance.
            A skill wraps a nested agent task: your definition must be a JSON object with:
              version: 1 (optional)
              parameters: JSON Schema describing the arguments the LLM passes when invoking this skill
              promptTemplate: markdown instructions for the nested task agent (the skill's documentation)
              allowTools: optional array of tool references the nested agent may call (e.g. ["builtin:chrome", "builtin:docwrite"])
            action=create: create a new skill AND bind it to the current agent instance (requires name, definition; optional description).
            action=list: list skills bound to the current agent instance (returns id, name, description, status per skill).
            action=get: get an existing skill bound to the current agent instance (requires skillId; returns full definition).
            action=update: edit a skill already bound to the current agent instance (requires skillId).
              Full mode (no operation): replace name/description/definition with the provided fields.
              Patch mode (operation given): edit the skill's promptTemplate markdown without rewriting everything:
                operation=replace_section: replace the section under a matching heading (requires headingSearch, content)
                operation=insert_after: insert new content after a matching heading (requires headingSearch, content)
                operation=replace_text: replace specific text in the promptTemplate (requires searchText, replaceText)
              Patch operations reuse the same Markdown engine as document editing, keeping parameters/allowTools intact.
            """;

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final CapabilitySkillSpi skillSpi;
    private final InstanceCapabilitySpi instanceCapabilitySpi;
    private final SessionSpi sessionSpi;

    @Override
    public BuiltinToolEnum getToolType() {
        return BuiltinToolEnum.SKILLWRITE;
    }

    @Override
    public boolean needConfig() {
        return false;
    }

    @Override
    public ToolInfoVO getInfo() {
        ToolInfoVO info = new ToolInfoVO();
        info.setName(BuiltinToolConstants.NAME_PREFIX + CapabilityBuiltinToolSkillOperation.class.getSimpleName());
        info.setDescription(TOOL_DESCRIPTION);
        info.setDisplayNameCn("技能管理");
        info.setDisplayNameEn("Skill Operation");
        info.setParamSchema(ToolSchemaUtil.generateParamSchema(SkillWriteExecuteContext.class));
        info.setResponseSchema(ToolSchemaUtil.generateParamSchema(SkillWriteResultVO.class));
        return info;
    }

    @Override
    public Class<? extends ExecuteContext> getContextType() {
        return SkillWriteExecuteContext.class;
    }

    @Override
    public Class<? extends ExecuteResult> getResultType() {
        return SkillWriteResultVO.class;
    }

    @Override
    public ExecuteResult execute(ExecuteContext ctx) {
        try {
            SkillWriteExecuteContext swCtx = (SkillWriteExecuteContext) ctx;
            String action = swCtx.getAction();
            if (action == null) {
                SkillWriteResultVO r = new SkillWriteResultVO();
                r.setAction("unknown");
                r.setPreview("action is required");
                return r;
            }
            Long skillId = swCtx.getSkillId();
            return switch (action) {
            case ACTION_CREATE -> handleCreate(swCtx);
            case ACTION_LIST -> handleList(swCtx);
            case ACTION_GET -> handleGet(swCtx, skillId);
            case ACTION_UPDATE -> handleUpdate(swCtx, skillId);
            default -> {
                log.warn("Unknown skillwrite action: {}", action);
                SkillWriteResultVO r = new SkillWriteResultVO();
                r.setAction(action);
                yield r;
            }
        };
        } catch (Exception e) {
            log.warn("skillwrite execute failed action={}", ctx instanceof SkillWriteExecuteContext s ? s.getAction() : "unknown", e);
            SkillWriteResultVO r = new SkillWriteResultVO();
            r.setAction("error");
            r.setPreview("Internal error: " + e.getMessage());
            return r;
        }
    }

    private SkillWriteResultVO handleCreate(SkillWriteExecuteContext ctx) {
        String name = ctx.getName();
        String definition = ctx.getDefinition();
        if (name == null || name.isBlank()) {
            return error(ACTION_CREATE, "name is required");
        }
        if (definition == null || definition.isBlank()) {
            return error(ACTION_CREATE, "definition is required (JSON with parameters + promptTemplate)");
        }
        String validation = validateDefinition(definition);
        if (validation != null) {
            return error(ACTION_CREATE, validation);
        }
        SessionContext session = resolveSession(ctx.getSessionId());
        if (session == null || session.instanceId() == null || session.instanceId() <= 0) {
            return error(ACTION_CREATE, "sessionId is required and must resolve to an agent instance");
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

        SkillWriteResultVO r = new SkillWriteResultVO();
        r.setAction(ACTION_CREATE);
        r.setSkillId(created.getId());
        r.setName(created.getName());
        r.setDescription(created.getDescription());
        r.setPreview(previewOf(definition));
        return r;
    }

    private SkillWriteResultVO handleList(SkillWriteExecuteContext ctx) {
        SessionContext session = resolveSession(ctx.getSessionId());
        if (session == null || session.instanceId() == null || session.instanceId() <= 0) {
            return error(ACTION_LIST, "sessionId is required and must resolve to an agent instance");
        }
        List<Long> boundIds = new ArrayList<>();
        for (CapabilityVO cap : instanceCapabilitySpi.getCapabilitiesByInstance(session.instanceId())) {
            if (CAPABILITY_TYPE_SKILL.equals(cap.getCapabilityType())) {
                boundIds.add(cap.getCapabilityId());
            }
        }
        List<SkillVO> skills = skillSpi.listSkillsByIds(boundIds);
        List<SkillWriteResultVO.SkillSummaryVO> summaries = skills.stream()
                .map(s -> {
                    SkillWriteResultVO.SkillSummaryVO item = new SkillWriteResultVO.SkillSummaryVO();
                    item.setSkillId(s.getId());
                    item.setName(s.getName());
                    item.setDescription(s.getDescription());
                    item.setStatus(s.getStatus());
                    item.setCreatedAt(s.getCreatedAt());
                    return item;
                })
                .toList();
        SkillWriteResultVO r = new SkillWriteResultVO();
        r.setAction(ACTION_LIST);
        r.setSkills(summaries);
        r.setPreview("Found " + summaries.size() + " skill(s) bound to the current agent instance");
        return r;
    }

    private SkillWriteResultVO handleGet(SkillWriteExecuteContext ctx, Long skillId) {
        if (skillId == null) {
            return error(ACTION_GET, "skillId is required");
        }
        SessionContext session = resolveSession(ctx.getSessionId());
        if (session == null || session.instanceId() == null || session.instanceId() <= 0) {
            return error(ACTION_GET, "sessionId is required and must resolve to an agent instance");
        }
        if (!isSkillBound(session.instanceId(), skillId)) {
            return error(ACTION_GET, "Skill " + skillId + " is not bound to the current agent instance");
        }
        SkillVO skill = skillSpi.getSkill(skillId);
        if (skill == null) {
            return error(ACTION_GET, "Skill not found: " + skillId);
        }
        SkillWriteResultVO r = new SkillWriteResultVO();
        r.setAction(ACTION_GET);
        r.setSkillId(skill.getId());
        r.setName(skill.getName());
        r.setDescription(skill.getDescription());
        r.setPreview(previewOf(skill.getDefinition()));
        return r;
    }

    private SkillWriteResultVO handleUpdate(SkillWriteExecuteContext ctx, Long skillId) {
        if (skillId == null) {
            return error(ACTION_UPDATE, "skillId is required");
        }
        SessionContext session = resolveSession(ctx.getSessionId());
        if (session == null || session.instanceId() == null || session.instanceId() <= 0) {
            return error(ACTION_UPDATE, "sessionId is required and must resolve to an agent instance");
        }
        if (!isSkillBound(session.instanceId(), skillId)) {
            return error(ACTION_UPDATE, "Skill " + skillId + " is not bound to the current agent instance");
        }
        SkillVO existing = skillSpi.getSkill(skillId);
        if (existing == null) {
            return error(ACTION_UPDATE, "Skill not found: " + skillId);
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
                    return error(ACTION_UPDATE, validation);
                }
            }
        } else {
            PatchedDefinition patched = applyMarkdownPatch(existing.getDefinition(), ctx);
            if (patched.error() != null) {
                SkillWriteResultVO r = error(ACTION_UPDATE, patched.error());
                r.setHeadings(patched.headings());
                r.setTotalLines(patched.totalLines());
                return r;
            }
            dto.setDefinition(patched.definition());
            String validation = validateDefinition(dto.getDefinition());
            if (validation != null) {
                return error(ACTION_UPDATE, validation);
            }
        }

        SkillVO updated = skillSpi.updateSkill(skillId, dto, session.createdBy());
        SkillWriteResultVO r = new SkillWriteResultVO();
        r.setAction(ACTION_UPDATE);
        r.setSkillId(updated.getId());
        r.setName(updated.getName());
        r.setDescription(updated.getDescription());
        r.setPreview(previewOf(updated.getDefinition()));
        return r;
    }

    /** 复用 doc 模块的 Markdown 工具编辑 skill definition 内嵌的 promptTemplate，保留 parameters/allowTools。 */
    private PatchedDefinition applyMarkdownPatch(String definition, SkillWriteExecuteContext ctx) {
        if (definition == null || definition.isBlank()) {
            return PatchedDefinition.error("definition is empty; cannot patch");
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
        JsonNode promptNode = root.get("promptTemplate");
        if (promptNode == null || !promptNode.isTextual()) {
            return PatchedDefinition.error("definition has no textual 'promptTemplate' to patch");
        }
        String prompt = promptNode.asText();
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
        ObjectNode rebuilt = root.deepCopy();
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

    private String validateDefinition(String definition) {
        try {
            SkillDefinitionParser.parse(definition);
            return null;
        } catch (InvalidSubRunDefinition e) {
            return "Invalid definition: " + e.getMessage();
        }
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
            log.warn("Failed to resolve session {} for skillwrite", sessionId, e);
            return null;
        }
    }

    private String previewOf(String definition) {
        if (definition == null) return "";
        String normalized = definition.replaceAll("[#*`\\n\\r]+", " ").trim();
        return normalized.length() > PREVIEW_LENGTH ? normalized.substring(0, PREVIEW_LENGTH) + "…" : normalized;
    }

    private SkillWriteResultVO error(String action, String message) {
        SkillWriteResultVO r = new SkillWriteResultVO();
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