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
import com.buukle.agent.capability.skill.dtvo.vo.SkillVO;
import com.buukle.agent.capability.skill.spi.CapabilitySkillSpi;
import com.buukle.agent.instance.dtvo.vo.CapabilityVO;
import com.buukle.agent.instance.dtvo.vo.SessionVO;
import com.buukle.agent.instance.spi.InstanceCapabilitySpi;
import com.buukle.agent.instance.spi.SessionSpi;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

import static com.buukle.agent.instance.dtvo.enums.InstanceCapabilityEnum.CAPABILITY_TYPE_SKILL;

@Slf4j
@Component
@RequiredArgsConstructor
public class CapabilityBuiltinToolSkillLibrary implements CapabilityBuiltinToolSpi {

    static final String ACTION_LIST = "list";
    static final String ACTION_COUNT = "count";
    static final String ACTION_SEARCH_TITLE = "search_by_title";
    static final String ACTION_GET = "get";

    static final int PREVIEW_LENGTH = 200;
    static final String MARKDOWN_FENCE = "```json";

    private static final String TOOL_DESCRIPTION = """
            发现与渐进式读取技能文档（list/count/search/get；get 可按大纲/段落/行范围读取）。
            """;

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final CapabilitySkillSpi skillSpi;
    private final InstanceCapabilitySpi instanceCapabilitySpi;
    private final SessionSpi sessionSpi;

    @Override
    public BuiltinToolEnum getToolType() {
        return BuiltinToolEnum.SKILL_LIBRARY;
    }

    @Override
    public boolean needConfig() {
        return false;
    }

    @Override
    public ToolInfoVO getInfo() {
        ToolInfoVO info = new ToolInfoVO();
        info.setName(BuiltinToolConstants.NAME_PREFIX + CapabilityBuiltinToolSkillLibrary.class.getSimpleName());
        info.setDescription(TOOL_DESCRIPTION);
        info.setDisplayNameCn("技能库");
        info.setDisplayNameEn("Skill Library");
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
            case ACTION_LIST -> handleList(swCtx);
            case ACTION_COUNT -> handleCount(swCtx);
            case ACTION_SEARCH_TITLE -> handleSearchByTitle(swCtx);
            case ACTION_GET -> handleGet(swCtx, skillId);
            default -> {
                log.warn("Unknown skill library action: {}", action);
                SkillWriteResultVO r = new SkillWriteResultVO();
                r.setAction(action);
                yield r;
            }
        };
        } catch (Exception e) {
            log.warn("skill library execute failed action={}", ctx instanceof SkillWriteExecuteContext s ? s.getAction() : "unknown", e);
            SkillWriteResultVO r = new SkillWriteResultVO();
            r.setAction("error");
            r.setPreview("Internal error: " + e.getMessage());
            return r;
        }
    }

    private SkillWriteResultVO handleList(SkillWriteExecuteContext ctx) {
        SessionContext session = resolveSession(ctx.getSessionId());
        if (session == null || session.instanceId() == null || session.instanceId() <= 0) {
            return error(ACTION_LIST, "sessionId is required and must resolve to an agent instance");
        }
        List<SkillVO> skills = boundSkills(session.instanceId());
        int page = Math.max(1, ctx.getPage());
        int pageSize = Math.max(1, ctx.getPageSize());
        List<SkillWriteResultVO.SkillSummaryVO> summaries = skillSummaries(paginate(skills, page, pageSize));
        SkillWriteResultVO r = new SkillWriteResultVO();
        r.setAction(ACTION_LIST);
        r.setSkills(summaries);
        r.setTotal(skills.size());
        r.setPreview("Found " + skills.size() + " skill(s) bound to the current agent instance");
        return r;
    }

    private SkillWriteResultVO handleCount(SkillWriteExecuteContext ctx) {
        SessionContext session = resolveSession(ctx.getSessionId());
        if (session == null || session.instanceId() == null || session.instanceId() <= 0) {
            return error(ACTION_COUNT, "sessionId is required and must resolve to an agent instance");
        }
        int total = boundSkills(session.instanceId()).size();
        SkillWriteResultVO r = new SkillWriteResultVO();
        r.setAction(ACTION_COUNT);
        r.setTotal(total);
        r.setPreview("Total skills bound to the current agent instance: " + total);
        return r;
    }

    private SkillWriteResultVO handleSearchByTitle(SkillWriteExecuteContext ctx) {
        SessionContext session = resolveSession(ctx.getSessionId());
        if (session == null || session.instanceId() == null || session.instanceId() <= 0) {
            return error(ACTION_SEARCH_TITLE, "sessionId is required and must resolve to an agent instance");
        }
        String keyword = ctx.getKeyword();
        if (keyword == null || keyword.isBlank()) {
            return error(ACTION_SEARCH_TITLE, "keyword is required");
        }
        String key = keyword.trim().toLowerCase(java.util.Locale.ROOT);
        List<SkillVO> matched = boundSkills(session.instanceId()).stream()
                .filter(s -> s.getName() != null && s.getName().toLowerCase(java.util.Locale.ROOT).contains(key))
                .toList();
        int page = Math.max(1, ctx.getPage());
        int pageSize = Math.max(1, ctx.getPageSize());
        SkillWriteResultVO r = new SkillWriteResultVO();
        r.setAction(ACTION_SEARCH_TITLE);
        r.setSkills(skillSummaries(paginate(matched, page, pageSize)));
        r.setTotal(matched.size());
        r.setPreview("Found " + matched.size() + " skill(s) matching \"" + keyword.trim() + "\"");
        return r;
    }

    private List<SkillVO> boundSkills(Long instanceId) {
        List<Long> boundIds = new ArrayList<>();
        for (CapabilityVO cap : instanceCapabilitySpi.getCapabilitiesByInstance(instanceId)) {
            if (CAPABILITY_TYPE_SKILL.equals(cap.getCapabilityType())) {
                boundIds.add(cap.getCapabilityId());
            }
        }
        return skillSpi.listSkillsByIds(boundIds);
    }

    private List<SkillWriteResultVO.SkillSummaryVO> skillSummaries(List<SkillVO> skills) {
        return skills.stream()
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
    }

    private static <T> List<T> paginate(List<T> items, int page, int pageSize) {
        if (items == null || items.isEmpty()) return List.of();
        int from = Math.min(items.size(), (page - 1) * pageSize);
        int to = Math.min(items.size(), from + pageSize);
        return items.subList(from, to);
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
        SkillWriteResultVO r = baseResult(skill);
        String definition = skill.getDefinition();
        if (definition == null || definition.isBlank()) {
            r.setPreview("Skill has no definition");
            return r;
        }
        String prompt = extractPromptTemplate(definition);
        int totalLines = prompt != null ? lineCount(prompt) : lineCount(definition);

        // Structure mode: 仅返回 promptTemplate 大纲（标题 + 行号）。
        if (Boolean.TRUE.equals(ctx.getStructure())) {
            if (prompt == null) {
                return withPreview(r, "definition has no textual 'promptTemplate' to outline");
            }
            List<HeadingInfo> headings = MarkdownParser.parseHeadings(prompt);
            r.setHeadings(headings);
            r.setTotalLines(totalLines);
            r.setTotal(headings.size());
            return r;
        }

        // Section mode: 按标题提取 promptTemplate 对应 section 正文。
        if (ctx.getSectionHeading() != null) {
            if (prompt == null) {
                return withPreview(r, "definition has no textual 'promptTemplate' to search sections");
            }
            String section = MarkdownParser.extractSection(prompt, ctx.getSectionHeading());
            if (section == null) {
                r.setPreview("Section not found: \"" + ctx.getSectionHeading() + "\". Available headings:");
                r.setHeadings(MarkdownParser.parseHeadings(prompt));
                r.setTotalLines(totalLines);
                return r;
            }
            r.setContent(section);
            r.setPreview(truncate(section));
            return r;
        }

        // Line range 模式: 按行号提取 promptTemplate 内容。
        if (ctx.getStartLine() != null) {
            if (prompt == null) {
                return withPreview(r, "definition has no textual 'promptTemplate' to extract lines");
            }
            String extracted = MarkdownParser.extractLines(prompt, ctx.getStartLine(), ctx.getEndLine());
            r.setContent(extracted);
            r.setPreview(truncate(extracted));
            return r;
        }

        // Default: 全文 definition（JSON）。
        r.setContent(definition);
        r.setPreview(previewOf(definition));
        r.setTotalLines(totalLines);
        return r;
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
            log.warn("Failed to resolve session {} for skill library", sessionId, e);
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

    private SkillWriteResultVO baseResult(SkillVO skill) {
        SkillWriteResultVO r = new SkillWriteResultVO();
        r.setAction(ACTION_GET);
        r.setSkillId(skill.getId());
        r.setName(skill.getName());
        r.setDescription(skill.getDescription());
        return r;
    }

    private static SkillWriteResultVO withPreview(SkillWriteResultVO r, String message) {
        r.setPreview(message);
        return r;
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

    private SkillWriteResultVO error(String action, String message) {
        SkillWriteResultVO r = new SkillWriteResultVO();
        r.setAction(action);
        r.setPreview(message);
        return r;
    }

    private static int lineCount(String s) {
        return s == null ? 0 : s.split("\n", -1).length;
    }

    private record SessionContext(Long instanceId, String createdBy) {
    }
}
