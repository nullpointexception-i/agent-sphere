package com.buukle.agent.capability.builtin.tool.skillwrite.tool;

import com.buukle.agent.capability.builtin.dtvo.enums.BuiltinToolEnum;
import com.buukle.agent.capability.builtin.tool.skillwrite.dtvo.dto.SkillLibraryExecuteContext;
import com.buukle.agent.capability.builtin.tool.skillwrite.dtvo.enums.SkillLibraryAction;
import com.buukle.agent.capability.builtin.tool.skillwrite.dtvo.vo.SkillLibraryResultVO;
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

    static final int PREVIEW_LENGTH = 200;
    static final String MARKDOWN_FENCE = "```json";

    private static final String TOOL_DESCRIPTION = """
            Discover and progressively read skill documents: list / count / search / search_by_title / get.
            Only these 5 actions are supported; to create/append/update skills use builtin_9 (skill manage), do not pass create/append/update to this tool.
            - list: return all bound skills; supports keyword filtering and page/pageSize pagination (default 20).
            - search (alias of search_by_title): find by skill-name keyword.
            - get: read by skillId or exact name; use structure for the outline, sectionHeading for one section, startLine/endLine for a line range.
            Skill body structure convention: # skill name + ## stepN step + ### stepN.M sub-step (the step identifier is the first word of the heading, e.g. step1 / step1.1).
            When adopting a skill: first get it, then record the step identifiers and skillId in your task list (todowrite's skillId/stepId);
            before executing a step, use get(skillId=..., sectionHeading=stepId) again to read that section body.
            Reading a skill never changes your task list.
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
        info.setParamSchema(ToolSchemaUtil.generateParamSchema(SkillLibraryExecuteContext.class));
        info.setResponseSchema(ToolSchemaUtil.generateParamSchema(SkillLibraryResultVO.class));
        return info;
    }

    @Override
    public Class<? extends ExecuteContext> getContextType() {
        return SkillLibraryExecuteContext.class;
    }

    @Override
    public Class<? extends ExecuteResult> getResultType() {
        return SkillLibraryResultVO.class;
    }

    @Override
    public ExecuteResult execute(ExecuteContext ctx) {
        try {
            SkillLibraryExecuteContext swCtx = (SkillLibraryExecuteContext) ctx;
            String action = swCtx.getAction();
            if (action == null || action.isBlank()) {
                SkillLibraryResultVO r = new SkillLibraryResultVO();
                r.setAction("unknown");
                r.setPreview("action is required. Allowed: list/count/search/search_by_title/get. "
                        + "To create/append/update skills, use builtin_9 (skill manage).");
                return r;
            }
            SkillLibraryAction act = SkillLibraryAction.from(action);
            if (act == null) {
                log.warn("Unknown skill library action: {}", action);
                return error(action, "Unsupported action: " + action
                        + ". Allowed: list/count/search/search_by_title/get. "
                        + "To create/append/update skills, use builtin_9 (skill manage).");
            }
            Long skillId = swCtx.getSkillId();
            return switch (act) {
                case LIST -> handleList(swCtx);
                case COUNT -> handleCount(swCtx);
                case SEARCH, SEARCH_BY_TITLE -> handleSearchByTitle(swCtx);
                case GET -> handleGet(swCtx, skillId);
            };
        } catch (Exception e) {
            log.warn("skill library execute failed action={}", ctx instanceof SkillLibraryExecuteContext s ? s.getAction() : "unknown", e);
            SkillLibraryResultVO r = new SkillLibraryResultVO();
            r.setAction("error");
            r.setPreview("Internal error: " + e.getMessage());
            return r;
        }
    }

    private SkillLibraryResultVO handleList(SkillLibraryExecuteContext ctx) {
        SessionContext session = resolveSession(ctx.getSessionId());
        if (session == null || session.instanceId() == null || session.instanceId() <= 0) {
            return error(SkillLibraryAction.LIST.value(), "sessionId is required and must resolve to an agent instance");
        }
        List<SkillVO> skills = boundSkills(session.instanceId());
        String keyword = ctx.getKeyword();
        if (keyword != null && !keyword.isBlank()) {
            String key = keyword.trim().toLowerCase(java.util.Locale.ROOT);
            skills = skills.stream()
                    .filter(s -> s.getName() != null
                            && s.getName().toLowerCase(java.util.Locale.ROOT).contains(key))
                    .toList();
        }
        int page = Math.max(1, ctx.getPage());
        int pageSize = Math.max(1, ctx.getPageSize());
        List<SkillLibraryResultVO.SkillSummaryVO> summaries = skillSummaries(paginate(skills, page, pageSize));
        SkillLibraryResultVO r = new SkillLibraryResultVO();
        r.setAction(SkillLibraryAction.LIST.value());
        r.setSkills(summaries);
        r.setTotal(skills.size());
        r.setPreview("Found " + skills.size() + " skill(s) bound to the current agent instance");
        return r;
    }

    private SkillLibraryResultVO handleCount(SkillLibraryExecuteContext ctx) {
        SessionContext session = resolveSession(ctx.getSessionId());
        if (session == null || session.instanceId() == null || session.instanceId() <= 0) {
            return error(SkillLibraryAction.COUNT.value(), "sessionId is required and must resolve to an agent instance");
        }
        int total = boundSkills(session.instanceId()).size();
        SkillLibraryResultVO r = new SkillLibraryResultVO();
        r.setAction(SkillLibraryAction.COUNT.value());
        r.setTotal(total);
        r.setPreview("Total skills bound to the current agent instance: " + total);
        return r;
    }

    private SkillLibraryResultVO handleSearchByTitle(SkillLibraryExecuteContext ctx) {
        SessionContext session = resolveSession(ctx.getSessionId());
        if (session == null || session.instanceId() == null || session.instanceId() <= 0) {
            return error(SkillLibraryAction.SEARCH_BY_TITLE.value(), "sessionId is required and must resolve to an agent instance");
        }
        String keyword = ctx.getKeyword();
        if (keyword == null || keyword.isBlank()) {
            return error(SkillLibraryAction.SEARCH_BY_TITLE.value(), "keyword is required");
        }
        String key = keyword.trim().toLowerCase(java.util.Locale.ROOT);
        List<SkillVO> matched = boundSkills(session.instanceId()).stream()
                .filter(s -> s.getName() != null && s.getName().toLowerCase(java.util.Locale.ROOT).contains(key))
                .toList();
        int page = Math.max(1, ctx.getPage());
        int pageSize = Math.max(1, ctx.getPageSize());
        SkillLibraryResultVO r = new SkillLibraryResultVO();
        r.setAction(SkillLibraryAction.SEARCH_BY_TITLE.value());
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

    private List<SkillLibraryResultVO.SkillSummaryVO> skillSummaries(List<SkillVO> skills) {
        return skills.stream()
                .map(s -> {
                    SkillLibraryResultVO.SkillSummaryVO item = new SkillLibraryResultVO.SkillSummaryVO();
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

    private SkillLibraryResultVO handleGet(SkillLibraryExecuteContext ctx, Long skillId) {
        SessionContext session = resolveSession(ctx.getSessionId());
        if (session == null || session.instanceId() == null || session.instanceId() <= 0) {
            return error(SkillLibraryAction.GET.value(), "sessionId is required and must resolve to an agent instance");
        }
        if (skillId == null && ctx.getName() != null && !ctx.getName().isBlank()) {
            String name = ctx.getName().trim();
            List<SkillVO> matches = boundSkills(session.instanceId()).stream()
                    .filter(s -> s.getName() != null && s.getName().equalsIgnoreCase(name))
                    .toList();
            if (matches.size() == 1) {
                skillId = matches.get(0).getId();
            } else if (matches.isEmpty()) {
                return error(SkillLibraryAction.GET.value(), "Skill name is not bound to the current agent instance: " + name);
            } else {
                return error(SkillLibraryAction.GET.value(), "Multiple skills match name \"" + name + "\": "
                        + matches.stream().map(SkillVO::getId).toList());
            }
        }
        if (skillId == null) {
            return error(SkillLibraryAction.GET.value(), "skillId or exact name is required");
        }
        if (!isSkillBound(session.instanceId(), skillId)) {
            return error(SkillLibraryAction.GET.value(), "Skill " + skillId + " is not bound to the current agent instance");
        }
        SkillVO skill = skillSpi.getSkill(skillId);
        if (skill == null) {
            return error(SkillLibraryAction.GET.value(), "Skill not found: " + skillId);
        }
        SkillLibraryResultVO r = baseResult(skill);
        String definition = skill.getDefinition();
        if (definition == null || definition.isBlank()) {
            r.setPreview("Skill has no definition");
            return r;
        }
        String prompt = extractPromptTemplate(definition);
        int totalLines = prompt != null ? lineCount(prompt) : lineCount(definition);

        // Structure mode: return only the promptTemplate outline (headings + line numbers).
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

        // Section mode: extract the promptTemplate section body matching the heading.
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

        // Line range mode: extract promptTemplate content by line number.
        if (ctx.getStartLine() != null) {
            if (prompt == null) {
                return withPreview(r, "definition has no textual 'promptTemplate' to extract lines");
            }
            String extracted = MarkdownParser.extractLines(prompt, ctx.getStartLine(), ctx.getEndLine());
            r.setContent(extracted);
            r.setPreview(truncate(extracted));
            return r;
        }

        // Default: full definition (JSON).
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

    private SkillLibraryResultVO baseResult(SkillVO skill) {
        SkillLibraryResultVO r = new SkillLibraryResultVO();
        r.setAction(SkillLibraryAction.GET.value());
        r.setSkillId(skill.getId());
        r.setName(skill.getName());
        r.setDescription(skill.getDescription());
        return r;
    }

    private static SkillLibraryResultVO withPreview(SkillLibraryResultVO r, String message) {
        r.setPreview(message);
        return r;
    }

    /** Extracts the promptTemplate text embedded in definition; returns null for non-object JSON or a non-textual promptTemplate. */
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

    private SkillLibraryResultVO error(String action, String message) {
        SkillLibraryResultVO r = new SkillLibraryResultVO();
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
