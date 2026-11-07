package com.buukle.agent.bootstrap.controller;

import com.buukle.agent.capability.builtin.tool.skillwrite.dtvo.dto.SkillLibraryExecuteContext;
import com.buukle.agent.capability.builtin.tool.skillwrite.dtvo.dto.SkillManageExecuteContext;
import com.buukle.agent.capability.builtin.tool.skillwrite.dtvo.vo.SkillLibraryResultVO;
import com.buukle.agent.capability.builtin.tool.skillwrite.dtvo.vo.SkillManageResultVO;
import com.buukle.agent.capability.builtin.tool.skillwrite.tool.CapabilityBuiltinToolSkillLibrary;
import com.buukle.agent.capability.builtin.tool.skillwrite.tool.CapabilityBuiltinToolSkillManage;
import com.buukle.agent.capability.skill.dtvo.dto.CreateSkillDTO;
import com.buukle.agent.capability.skill.dtvo.vo.SkillVO;
import com.buukle.agent.capability.skill.spi.CapabilitySkillSpi;
import com.buukle.agent.instance.dtvo.dto.CreateInstanceCapabilityDTO;
import com.buukle.agent.instance.dtvo.vo.CapabilityVO;
import com.buukle.agent.instance.dtvo.vo.SessionVO;
import com.buukle.agent.instance.spi.InstanceCapabilitySpi;
import com.buukle.agent.instance.spi.SessionSpi;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CapabilityBuiltinToolSkillOperationTest {

    private static final String VALID_DEFINITION = """
            {"version":1,"parameters":{"type":"object","properties":{}},"promptTemplate":"# 面试助手\\n\\n## step1 任务\\n### step1.1 分析\\n按 JD 分析候选人\\n\\n## step2 输出\\n返回 JSON"}""";

    private CapabilitySkillSpi skillSpi;
    private InstanceCapabilitySpi instanceCapabilitySpi;
    private SessionSpi sessionSpi;
    private CapabilityBuiltinToolSkillLibrary libraryTool;
    private CapabilityBuiltinToolSkillManage manageTool;

    @BeforeEach
    void setUp() {
        skillSpi = mock(CapabilitySkillSpi.class);
        instanceCapabilitySpi = mock(InstanceCapabilitySpi.class);
        sessionSpi = mock(SessionSpi.class);
        libraryTool = new CapabilityBuiltinToolSkillLibrary(skillSpi, instanceCapabilitySpi, sessionSpi);
        manageTool = new CapabilityBuiltinToolSkillManage(skillSpi, instanceCapabilitySpi, sessionSpi);
    }

    @Test
    void create_bindsSkillToCurrentInstance() {
        when(sessionSpi.getSession(1L)).thenReturn(sessionOf(1L, "alice"));
        SkillVO created = skill(100L, "fetchCandidate", "简历抓取", VALID_DEFINITION);
        when(skillSpi.createSkill(any(CreateSkillDTO.class), eq("alice"))).thenReturn(created);

        SkillManageExecuteContext ctx = new SkillManageExecuteContext();
        ctx.setSessionId(1L);
        ctx.setAction("create");
        ctx.setName("fetchCandidate");
        ctx.setDescription("简历抓取");
        ctx.setDefinition(VALID_DEFINITION);

        SkillManageResultVO r = (SkillManageResultVO) manageTool.execute(ctx);

        assertEquals("create", r.getAction());
        assertEquals(100L, r.getSkillId());
        assertNotNull(r.getPreview());
        verify(skillSpi).createSkill(any(CreateSkillDTO.class), eq("alice"));
        verify(instanceCapabilitySpi).createCapability(argThat(dto ->
                dto.getInstanceId().equals(1L)
                        && "skill".equals(dto.getCapabilityType())
                        && dto.getCapabilityId().equals(100L)
                        && "ENABLED".equals(dto.getStatus())));
    }

    @Test
    void create_invalidDefinition_doesNotCreateNorBind() {
        when(sessionSpi.getSession(1L)).thenReturn(sessionOf(1L, "alice"));

        SkillManageExecuteContext ctx = new SkillManageExecuteContext();
        ctx.setSessionId(1L);
        ctx.setAction("create");
        ctx.setName("bad");
        ctx.setDefinition("not-json");

        SkillManageResultVO r = (SkillManageResultVO) manageTool.execute(ctx);

        assertTrue(r.getPreview().contains("Invalid definition"));
        verify(skillSpi, never()).createSkill(any(CreateSkillDTO.class), any());
        verify(instanceCapabilitySpi, never()).createCapability(any(CreateInstanceCapabilityDTO.class));
    }

    @Test
    void create_nonConformantStructure_rejectedWithTemplate() {
        // Invalid structure: no H1 and no ## stepN steps — must be rejected before persisting, with the template returned
        String bad = """
                {"version":1,"parameters":{"type":"object","properties":{}},"promptTemplate":"## 任务\\nold body"}""";

        SkillManageExecuteContext ctx = new SkillManageExecuteContext();
        ctx.setSessionId(1L);
        ctx.setAction("create");
        ctx.setName("bad");
        ctx.setDefinition(bad);

        SkillManageResultVO r = (SkillManageResultVO) manageTool.execute(ctx);

        assertTrue(r.getPreview().contains("does not conform"), r.getPreview());
        assertTrue(r.getPreview().contains("# <Skill name>"), "should include the standard template: " + r.getPreview());
        verify(skillSpi, never()).createSkill(any(CreateSkillDTO.class), any());
        verify(instanceCapabilitySpi, never()).createCapability(any(CreateInstanceCapabilityDTO.class));
    }

    @Test
    void create_missingResolvedInstance_returnsError() {
        when(sessionSpi.getSession(1L)).thenReturn(sessionOf(null, "alice"));

        SkillManageExecuteContext ctx = new SkillManageExecuteContext();
        ctx.setSessionId(1L);
        ctx.setAction("create");
        ctx.setName("orphan");
        ctx.setDefinition(VALID_DEFINITION);

        SkillManageResultVO r = (SkillManageResultVO) manageTool.execute(ctx);

        assertTrue(r.getPreview().contains("agent instance"));
        verify(skillSpi, never()).createSkill(any(CreateSkillDTO.class), any());
    }

    @Test
    void list_returnsOnlySkillCapabilitiesBoundToInstance() {
        when(sessionSpi.getSession(1L)).thenReturn(sessionOf(1L, "alice"));
        when(instanceCapabilitySpi.getCapabilitiesByInstance(1L)).thenReturn(List.of(
                cap("skill", 100L), cap("builtin", 5L)));
        when(skillSpi.listSkillsByIds(List.of(100L))).thenReturn(List.of(skill(100L, "a", null, null)));

        SkillLibraryExecuteContext ctx = new SkillLibraryExecuteContext();
        ctx.setSessionId(1L);
        ctx.setAction("list");

        SkillLibraryResultVO r = (SkillLibraryResultVO) libraryTool.execute(ctx);

        assertEquals("list", r.getAction());
        assertEquals(1, r.getSkills().size());
        assertEquals(100L, r.getSkills().get(0).getSkillId());
        assertTrue(r.getPreview().contains("1 skill(s)"));
    }

    @Test
    void list_keywordFiltersBoundSkills() {
        when(sessionSpi.getSession(1L)).thenReturn(sessionOf(1L, "alice"));
        when(instanceCapabilitySpi.getCapabilitiesByInstance(1L)).thenReturn(List.of(
                cap("skill", 100L), cap("skill", 101L)));
        when(skillSpi.listSkillsByIds(List.of(100L, 101L))).thenReturn(List.of(
                skill(100L, "boss-chat-audit-and-greet", null, VALID_DEFINITION),
                skill(101L, "resume-optimizer", null, VALID_DEFINITION)));

        SkillLibraryExecuteContext ctx = new SkillLibraryExecuteContext();
        ctx.setSessionId(1L);
        ctx.setAction("list");
        ctx.setKeyword("boss");

        SkillLibraryResultVO r = (SkillLibraryResultVO) libraryTool.execute(ctx);

        assertEquals(1, r.getSkills().size());
        assertEquals(100L, r.getSkills().get(0).getSkillId());
    }

    @Test
    void searchAliasMatchesSkillName() {
        when(sessionSpi.getSession(1L)).thenReturn(sessionOf(1L, "alice"));
        when(instanceCapabilitySpi.getCapabilitiesByInstance(1L)).thenReturn(List.of(cap("skill", 100L)));
        when(skillSpi.listSkillsByIds(List.of(100L))).thenReturn(List.of(
                skill(100L, "boss-chat-audit-and-greet", null, VALID_DEFINITION)));

        SkillLibraryExecuteContext ctx = new SkillLibraryExecuteContext();
        ctx.setSessionId(1L);
        ctx.setAction("search");
        ctx.setKeyword("greet");

        SkillLibraryResultVO r = (SkillLibraryResultVO) libraryTool.execute(ctx);

        assertEquals(1, r.getSkills().size());
        assertEquals(100L, r.getSkills().get(0).getSkillId());
    }

    @Test
    void get_resolvesBoundSkillByExactName() {
        when(sessionSpi.getSession(1L)).thenReturn(sessionOf(1L, "alice"));
        when(instanceCapabilitySpi.getCapabilitiesByInstance(1L)).thenReturn(List.of(cap("skill", 100L)));
        when(skillSpi.listSkillsByIds(List.of(100L))).thenReturn(List.of(
                skill(100L, "boss-chat-audit-and-greet", null, VALID_DEFINITION)));
        when(skillSpi.getSkill(100L)).thenReturn(skill(100L, "boss-chat-audit-and-greet", null, VALID_DEFINITION));

        SkillLibraryExecuteContext ctx = new SkillLibraryExecuteContext();
        ctx.setSessionId(1L);
        ctx.setAction("get");
        ctx.setName("boss-chat-audit-and-greet");

        SkillLibraryResultVO r = (SkillLibraryResultVO) libraryTool.execute(ctx);

        assertEquals(100L, r.getSkillId());
        assertNotNull(r.getContent());
    }

    @Test
    void unknownActionReturnsAllowedActions() {
        SkillLibraryExecuteContext ctx = new SkillLibraryExecuteContext();
        ctx.setSessionId(1L);
        ctx.setAction("bogus");

        SkillLibraryResultVO r = (SkillLibraryResultVO) libraryTool.execute(ctx);

        assertTrue(r.getPreview().contains("Allowed: list/count/search/search_by_title/get"));
    }

    @Test
    void get_rejectsSkillNotBoundToCurrentInstance() {
        when(sessionSpi.getSession(1L)).thenReturn(sessionOf(1L, "alice"));
        when(instanceCapabilitySpi.getCapabilitiesByInstance(1L)).thenReturn(List.of(cap("skill", 200L)));

        SkillLibraryExecuteContext ctx = new SkillLibraryExecuteContext();
        ctx.setSessionId(1L);
        ctx.setAction("get");
        ctx.setSkillId(100L);

        SkillLibraryResultVO r = (SkillLibraryResultVO) libraryTool.execute(ctx);

        assertTrue(r.getPreview().contains("not bound"));
        verify(skillSpi, never()).getSkill(100L);
    }

    @Test
    void get_returnsBoundSkillDefinition() {
        when(sessionSpi.getSession(1L)).thenReturn(sessionOf(1L, "alice"));
        when(instanceCapabilitySpi.getCapabilitiesByInstance(1L)).thenReturn(List.of(cap("skill", 100L)));
        when(skillSpi.getSkill(100L)).thenReturn(skill(100L, "a", null, VALID_DEFINITION));

        SkillLibraryExecuteContext ctx = new SkillLibraryExecuteContext();
        ctx.setSessionId(1L);
        ctx.setAction("get");
        ctx.setSkillId(100L);

        SkillLibraryResultVO r = (SkillLibraryResultVO) libraryTool.execute(ctx);

        assertEquals(100L, r.getSkillId());
        assertNotNull(r.getPreview());
    }

    @Test
    void update_full_replacesDefinitionWithOperator() {
        when(sessionSpi.getSession(1L)).thenReturn(sessionOf(1L, "alice"));
        when(instanceCapabilitySpi.getCapabilitiesByInstance(1L)).thenReturn(List.of(cap("skill", 100L)));
        when(skillSpi.getSkill(100L)).thenReturn(skill(100L, "a", null, VALID_DEFINITION));
        when(skillSpi.updateSkill(eq(100L), any(CreateSkillDTO.class), eq("alice")))
                .thenReturn(skill(100L, "a", null, VALID_DEFINITION));

        SkillManageExecuteContext ctx = new SkillManageExecuteContext();
        ctx.setSessionId(1L);
        ctx.setAction("update");
        ctx.setSkillId(100L);
        ctx.setDefinition(VALID_DEFINITION);

        SkillManageResultVO r = (SkillManageResultVO) manageTool.execute(ctx);

        assertEquals("update", r.getAction());
        verify(skillSpi).updateSkill(eq(100L), any(CreateSkillDTO.class), eq("alice"));
    }

    @Test
    void update_rejectsSkillNotBoundToCurrentInstance() {
        when(sessionSpi.getSession(1L)).thenReturn(sessionOf(1L, "alice"));
        when(instanceCapabilitySpi.getCapabilitiesByInstance(1L)).thenReturn(List.of(cap("skill", 200L)));

        SkillManageExecuteContext ctx = new SkillManageExecuteContext();
        ctx.setSessionId(1L);
        ctx.setAction("update");
        ctx.setSkillId(100L);
        ctx.setDefinition(VALID_DEFINITION);

        SkillManageResultVO r = (SkillManageResultVO) manageTool.execute(ctx);

        assertTrue(r.getPreview().contains("not bound"));
        verify(skillSpi, never()).updateSkill(any(), any(), any());
    }

    @Test
    void update_patch_replaceSection_preservesParameters() {
        String definition = """
                {"version":1,
                 "parameters":{"type":"object","properties":{"keyword":{"type":"string"}}},
                 "promptTemplate":"# 面试助手\\n\\n## step1 任务\\nold body\\n\\n## step2 输出\\nout"}""";
        when(sessionSpi.getSession(1L)).thenReturn(sessionOf(1L, "alice"));
        when(instanceCapabilitySpi.getCapabilitiesByInstance(1L)).thenReturn(List.of(cap("skill", 100L)));
        when(skillSpi.getSkill(100L)).thenReturn(skill(100L, "a", null, definition));
        when(skillSpi.updateSkill(eq(100L), any(CreateSkillDTO.class), eq("alice")))
                .thenReturn(skill(100L, "a", null, definition));

        SkillManageExecuteContext ctx = new SkillManageExecuteContext();
        ctx.setSessionId(1L);
        ctx.setAction("update");
        ctx.setSkillId(100L);
        ctx.setOperation("replace_section");
        ctx.setHeadingSearch("任务");
        ctx.setContent("new body");

        SkillManageResultVO r = (SkillManageResultVO) manageTool.execute(ctx);

        assertEquals("update", r.getAction());
        org.mockito.ArgumentCaptor<CreateSkillDTO> captor = org.mockito.ArgumentCaptor.forClass(CreateSkillDTO.class);
        verify(skillSpi).updateSkill(eq(100L), captor.capture(), eq("alice"));
        String patched = captor.getValue().getDefinition();
        assertTrue(patched.contains("new body"), "patch content should be present");
        assertFalse(patched.contains("old body"), "old section should be replaced");
        assertTrue(patched.contains("\"keyword\""), "parameters should be preserved");
    }

    @Test
    void update_patch_unknownHeading_returnsHeadingsHint() {
        String definition = """
                {"version":1,"parameters":{"type":"object","properties":{}},"promptTemplate":"## 任务\\nbody"}""";
        when(sessionSpi.getSession(1L)).thenReturn(sessionOf(1L, "alice"));
        when(instanceCapabilitySpi.getCapabilitiesByInstance(1L)).thenReturn(List.of(cap("skill", 100L)));
        when(skillSpi.getSkill(100L)).thenReturn(skill(100L, "a", null, definition));

        SkillManageExecuteContext ctx = new SkillManageExecuteContext();
        ctx.setSessionId(1L);
        ctx.setAction("update");
        ctx.setSkillId(100L);
        ctx.setOperation("replace_section");
        ctx.setHeadingSearch("不存在的标题");
        ctx.setContent("x");

        SkillManageResultVO r = (SkillManageResultVO) manageTool.execute(ctx);

        assertTrue(r.getPreview().contains("Section not found"));
        assertNotNull(r.getHeadings());
        assertFalse(r.getHeadings().isEmpty());
        verify(skillSpi, never()).updateSkill(any(), any(), any());
    }

    @Test
    void get_structure_true_returnsPromptTemplateOutline() {
        String definition = """
                {"version":1,"parameters":{"type":"object","properties":{}},"promptTemplate":"## 任务\\n按 JD 分析候选人\\n\\n## 输出\\n返回 JSON"}""";
        when(sessionSpi.getSession(1L)).thenReturn(sessionOf(1L, "alice"));
        when(instanceCapabilitySpi.getCapabilitiesByInstance(1L)).thenReturn(List.of(cap("skill", 100L)));
        when(skillSpi.getSkill(100L)).thenReturn(skill(100L, "a", null, definition));

        SkillLibraryExecuteContext ctx = new SkillLibraryExecuteContext();
        ctx.setSessionId(1L);
        ctx.setAction("get");
        ctx.setSkillId(100L);
        ctx.setStructure(true);

        SkillLibraryResultVO r = (SkillLibraryResultVO) libraryTool.execute(ctx);

        assertNotNull(r.getHeadings());
        assertEquals(2, r.getHeadings().size());
        assertEquals(1, r.getHeadings().get(0).getLine());
        assertEquals("任务", r.getHeadings().get(0).getText());
        assertEquals(4, r.getHeadings().get(1).getLine());
        assertEquals(Integer.valueOf(5), r.getTotalLines());
        assertEquals(Integer.valueOf(2), r.getTotal());
    }

    @Test
    void get_sectionHeading_returnsSectionContent() {
        String definition = """
                {"version":1,"parameters":{"type":"object","properties":{}},"promptTemplate":"## 任务\\n按 JD 分析候选人\\n\\n## 输出\\n返回 JSON"}""";
        when(sessionSpi.getSession(1L)).thenReturn(sessionOf(1L, "alice"));
        when(instanceCapabilitySpi.getCapabilitiesByInstance(1L)).thenReturn(List.of(cap("skill", 100L)));
        when(skillSpi.getSkill(100L)).thenReturn(skill(100L, "a", null, definition));

        SkillLibraryExecuteContext ctx = new SkillLibraryExecuteContext();
        ctx.setSessionId(1L);
        ctx.setAction("get");
        ctx.setSkillId(100L);
        ctx.setSectionHeading("任务");

        SkillLibraryResultVO r = (SkillLibraryResultVO) libraryTool.execute(ctx);

        assertNotNull(r.getContent());
        assertTrue(r.getContent().contains("按 JD 分析候选人"));
        assertFalse(r.getContent().contains("返回 JSON"));
    }

    @Test
    void get_sectionHeading_notFound_returnsHeadingsHint() {
        String definition = """
                {"version":1,"parameters":{"type":"object","properties":{}},"promptTemplate":"## 任务\\nbody"}""";
        when(sessionSpi.getSession(1L)).thenReturn(sessionOf(1L, "alice"));
        when(instanceCapabilitySpi.getCapabilitiesByInstance(1L)).thenReturn(List.of(cap("skill", 100L)));
        when(skillSpi.getSkill(100L)).thenReturn(skill(100L, "a", null, definition));

        SkillLibraryExecuteContext ctx = new SkillLibraryExecuteContext();
        ctx.setSessionId(1L);
        ctx.setAction("get");
        ctx.setSkillId(100L);
        ctx.setSectionHeading("不存在的标题");

        SkillLibraryResultVO r = (SkillLibraryResultVO) libraryTool.execute(ctx);

        assertTrue(r.getPreview().contains("Section not found"));
        assertNotNull(r.getHeadings());
        assertFalse(r.getHeadings().isEmpty());
        assertNotNull(r.getTotalLines());
    }

    @Test
    void get_startLineEndLine_returnsLineRange() {
        String definition = """
                {"version":1,"parameters":{"type":"object","properties":{}},"promptTemplate":"## 任务\\n按 JD 分析候选人\\n\\n## 输出\\n返回 JSON"}""";
        when(sessionSpi.getSession(1L)).thenReturn(sessionOf(1L, "alice"));
        when(instanceCapabilitySpi.getCapabilitiesByInstance(1L)).thenReturn(List.of(cap("skill", 100L)));
        when(skillSpi.getSkill(100L)).thenReturn(skill(100L, "a", null, definition));

        SkillLibraryExecuteContext ctx = new SkillLibraryExecuteContext();
        ctx.setSessionId(1L);
        ctx.setAction("get");
        ctx.setSkillId(100L);
        ctx.setStartLine(2);
        ctx.setEndLine(2);

        SkillLibraryResultVO r = (SkillLibraryResultVO) libraryTool.execute(ctx);

        assertEquals("按 JD 分析候选人", r.getContent());
    }

    @Test
    void get_full_returnsCompleteDefinitionAsContent() {
        when(sessionSpi.getSession(1L)).thenReturn(sessionOf(1L, "alice"));
        when(instanceCapabilitySpi.getCapabilitiesByInstance(1L)).thenReturn(List.of(cap("skill", 100L)));
        when(skillSpi.getSkill(100L)).thenReturn(skill(100L, "a", null, VALID_DEFINITION));

        SkillLibraryExecuteContext ctx = new SkillLibraryExecuteContext();
        ctx.setSessionId(1L);
        ctx.setAction("get");
        ctx.setSkillId(100L);

        SkillLibraryResultVO r = (SkillLibraryResultVO) libraryTool.execute(ctx);

        assertEquals(VALID_DEFINITION, r.getContent());
        assertNotNull(r.getPreview());
        assertNotNull(r.getTotalLines());
    }

    @Test
    void count_returnsTotalBoundSkills() {
        when(sessionSpi.getSession(1L)).thenReturn(sessionOf(1L, "alice"));
        when(instanceCapabilitySpi.getCapabilitiesByInstance(1L)).thenReturn(List.of(
                cap("skill", 100L), cap("skill", 200L), cap("builtin", 5L)));
        when(skillSpi.listSkillsByIds(List.of(100L, 200L)))
                .thenReturn(List.of(skill(100L, "a", null, null), skill(200L, "b", null, null)));

        SkillLibraryExecuteContext ctx = new SkillLibraryExecuteContext();
        ctx.setSessionId(1L);
        ctx.setAction("count");

        SkillLibraryResultVO r = (SkillLibraryResultVO) libraryTool.execute(ctx);

        assertEquals("count", r.getAction());
        assertEquals(Integer.valueOf(2), r.getTotal());
        assertTrue(r.getPreview().contains("2"));
    }

    @Test
    void search_byTitle_filtersBoundSkillsByName() {
        when(sessionSpi.getSession(1L)).thenReturn(sessionOf(1L, "alice"));
        when(instanceCapabilitySpi.getCapabilitiesByInstance(1L)).thenReturn(List.of(
                cap("skill", 100L), cap("skill", 200L)));
        when(skillSpi.listSkillsByIds(List.of(100L, 200L)))
                .thenReturn(List.of(skill(100L, "fetchCandidate", null, null), skill(200L, "resumeParser", null, null)));

        SkillLibraryExecuteContext ctx = new SkillLibraryExecuteContext();
        ctx.setSessionId(1L);
        ctx.setAction("search_by_title");
        ctx.setKeyword("fetch");

        SkillLibraryResultVO r = (SkillLibraryResultVO) libraryTool.execute(ctx);

        assertEquals("search_by_title", r.getAction());
        assertEquals(1, r.getSkills().size());
        assertEquals(100L, r.getSkills().get(0).getSkillId());
        assertEquals(Integer.valueOf(1), r.getTotal());
    }

    @Test
    void append_appendsToPromptTemplateAndPreservesParameters() {
        String definition = """
                {"version":1,
                 "parameters":{"type":"object","properties":{"keyword":{"type":"string"}}},
                 "promptTemplate":"# 面试助手\\n## step1 任务\\nold body"}""";
        when(sessionSpi.getSession(1L)).thenReturn(sessionOf(1L, "alice"));
        when(instanceCapabilitySpi.getCapabilitiesByInstance(1L)).thenReturn(List.of(cap("skill", 100L)));
        when(skillSpi.getSkill(100L)).thenReturn(skill(100L, "a", null, definition));
        when(skillSpi.updateSkill(eq(100L), any(CreateSkillDTO.class), eq("alice")))
                .thenReturn(skill(100L, "a", null, definition));

        SkillManageExecuteContext ctx = new SkillManageExecuteContext();
        ctx.setSessionId(1L);
        ctx.setAction("append");
        ctx.setSkillId(100L);
        ctx.setContent("## 补充\nextra notes");

        SkillManageResultVO r = (SkillManageResultVO) manageTool.execute(ctx);

        assertEquals("append", r.getAction());
        org.mockito.ArgumentCaptor<CreateSkillDTO> captor = org.mockito.ArgumentCaptor.forClass(CreateSkillDTO.class);
        verify(skillSpi).updateSkill(eq(100L), captor.capture(), eq("alice"));
        String appended = captor.getValue().getDefinition();
        assertTrue(appended.contains("## 补充"), "appended content should be present");
        assertTrue(appended.contains("\"keyword\""), "parameters should be preserved");
        assertTrue(appended.contains("old body"), "original promptTemplate should be preserved");
    }

    @Test
    void manage_schemaOnlyContainsManageFields() {
        String schema = manageTool.getInfo().getParamSchema();

        assertTrue(schema.contains("\"definition\""), schema);
        assertTrue(schema.contains("\"operation\""), schema);
        assertTrue(schema.contains("\"create\""), schema);
        assertFalse(schema.contains("\"pageSize\""), schema);
        assertFalse(schema.contains("\"sectionHeading\""), schema);
        assertFalse(schema.contains("\"keyword\""), schema);
    }

    @Test
    void library_schemaOnlyContainsLibraryFields() {
        String schema = libraryTool.getInfo().getParamSchema();

        assertTrue(schema.contains("\"pageSize\""), schema);
        assertTrue(schema.contains("\"sectionHeading\""), schema);
        assertTrue(schema.contains("\"list\""), schema);
        assertFalse(schema.contains("\"definition\""), schema);
        assertFalse(schema.contains("\"operation\""), schema);
        assertFalse(schema.contains("\"headingSearch\""), schema);
    }

    @Test
    void manage_listReturnsActionableError() {
        SkillManageExecuteContext ctx = new SkillManageExecuteContext();
        ctx.setAction("list");

        SkillManageResultVO r = (SkillManageResultVO) manageTool.execute(ctx);

        assertTrue(r.getPreview().contains("builtin_8"), r.getPreview());
        assertTrue(r.getPreview().contains("create/append/update"), r.getPreview());
    }

    @Test
    void library_createReturnsActionableError() {
        SkillLibraryExecuteContext ctx = new SkillLibraryExecuteContext();
        ctx.setAction("create");

        SkillLibraryResultVO r = (SkillLibraryResultVO) libraryTool.execute(ctx);

        assertTrue(r.getPreview().contains("builtin_9"), r.getPreview());
    }

    private SessionVO sessionOf(Long instanceId, String createdBy) {
        SessionVO vo = new SessionVO();
        vo.setId(1L);
        vo.setAgentInstanceId(instanceId);
        vo.setCreatedBy(createdBy);
        return vo;
    }

    private CapabilityVO cap(String type, long capabilityId) {
        CapabilityVO vo = new CapabilityVO();
        vo.setId(1L);
        vo.setCapabilityType(type);
        vo.setCapabilityId(capabilityId);
        vo.setStatus("ENABLED");
        return vo;
    }

    private SkillVO skill(Long id, String name, String description, String definition) {
        SkillVO vo = new SkillVO();
        vo.setId(id);
        vo.setName(name);
        vo.setDescription(description);
        vo.setDefinition(definition);
        vo.setStatus("ENABLED");
        return vo;
    }
}
