package com.buukle.agent.bootstrap.controller;

import com.buukle.agent.common.constant.GroupRouterPrompt;
import com.buukle.agent.instance.domain.AgentInstance;
import com.buukle.agent.instance.dtvo.dto.CreateInstanceDTO;
import com.buukle.agent.instance.dtvo.enums.InstanceRole;
import com.buukle.agent.instance.dtvo.vo.InstanceVO;
import com.buukle.agent.instance.service.converter.InstanceConverter;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Field pass-through for session source switches (V89).
 *
 * <p>`InstanceConverter` is hand-written; missing one direction raises no error — it just makes the switch an admin set in the main site
 * **fail silently** (the VO cannot read it, or the DTO cannot write it). Both directions must be locked.
 */
class InstanceConverterTest {

    private final InstanceConverter converter = new InstanceConverter();

    @Test
    void sessionSwitches_roundTripBothDirections() {
        InstanceVO vo = new InstanceVO();
        vo.setName("Headhunter Assist");
        vo.setAllowConsoleSession(true);
        vo.setAllowWidgetSession(false);

        AgentInstance do_ = converter.toDO(toCreateDto(true, false));
        // DO → VO
        do_.setId(1L);
        InstanceVO back = converter.toVO(do_);
        assertEquals(true, back.getAllowConsoleSession());
        assertEquals(false, back.getAllowWidgetSession());
        // CreateDTO → DO
        assertEquals(true, do_.getAllowConsoleSession(), "DTO → DO 必须带上主站开关");
        assertEquals(false, do_.getAllowWidgetSession(), "DTO → DO 必须带上 widget 开关");
    }

    @Test
    void nullSwitches_stayNullSoUpdateDoesNotResetThem() {
        // Key point: a blank value must pass through as null unchanged. MyBatis-Plus ignores null fields —
        // if a default were filled in here, any update that omits these two fields would reset the admin's choice back to the default.
        AgentInstance do_ = converter.toDO(toCreateDto(null, null));

        assertNull(do_.getAllowConsoleSession());
        assertNull(do_.getAllowWidgetSession());
        assertNull(converter.toVO(do_).getAllowConsoleSession());
        assertNull(converter.toVO(do_).getAllowWidgetSession());
    }

    @Test
    void completionProtocol_modelDefaultClearsOverride() {
        CreateInstanceDTO inherit = new CreateInstanceDTO();
        inherit.setName("chat");
        inherit.setCompletionProtocol("MODEL_DEFAULT");
        assertNull(converter.toDO(inherit).getCompletionProtocol(),
                "MODEL_DEFAULT 必须落为 NULL，表示跟随模型路由");

        CreateInstanceDTO strict = new CreateInstanceDTO();
        strict.setName("task");
        strict.setCompletionProtocol("STRICT_FINISH");
        assertEquals("STRICT_FINISH", converter.toDO(strict).getCompletionProtocol());

        AgentInstance existing = new AgentInstance();
        existing.setCompletionProtocol("IMPLICIT_TEXT");
        assertEquals("IMPLICIT_TEXT", converter.toVO(existing).getCompletionProtocol());
    }

    @Test
    void groupRouterWithoutPrompt_echoesSystemDefault() {
        AgentInstance instance = new AgentInstance();
        instance.setInstanceRole(InstanceRole.GROUP_ROUTER.value());

        InstanceVO vo = converter.toVO(instance);

        assertEquals(GroupRouterPrompt.DEFAULT, vo.getSystemPrompt());
        assertEquals(true, vo.getSystemPromptDefaulted());
        assertEquals(GroupRouterPrompt.DEFAULT, vo.getDefaultSystemPrompt());
    }

    @Test
    void groupRouterWithCustomPrompt_keepsIt() {
        AgentInstance instance = new AgentInstance();
        instance.setInstanceRole(InstanceRole.GROUP_ROUTER.value());
        instance.setSystemPrompt("custom routing rules");

        InstanceVO vo = converter.toVO(instance);

        assertEquals("custom routing rules", vo.getSystemPrompt());
        assertFalse(vo.getSystemPromptDefaulted());
    }

    @Test
    void chatInstanceWithoutPrompt_isNotDefaulted() {
        AgentInstance instance = new AgentInstance();
        instance.setInstanceRole(InstanceRole.CHAT.value());

        InstanceVO vo = converter.toVO(instance);

        assertNull(vo.getSystemPrompt());
        assertFalse(vo.getSystemPromptDefaulted());
        assertNull(vo.getDefaultSystemPrompt());
    }

    private static CreateInstanceDTO toCreateDto(Boolean console, Boolean widget) {
        CreateInstanceDTO dto = new CreateInstanceDTO();
        dto.setName("Headhunter Assist");
        dto.setAllowConsoleSession(console);
        dto.setAllowWidgetSession(widget);
        return dto;
    }
}
