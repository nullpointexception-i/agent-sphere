package com.buukle.agent.bootstrap.controller;

import com.buukle.agent.instance.domain.AgentInstance;
import com.buukle.agent.instance.dtvo.dto.CreateInstanceDTO;
import com.buukle.agent.instance.dtvo.vo.InstanceVO;
import com.buukle.agent.instance.service.converter.InstanceConverter;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 会话来源开关的字段贯通（V89）。
 *
 * <p>`InstanceConverter` 是手写的，漏掉一个方向不会报错 —— 只会让管理员在主站设的开关
 * **静默失效**（VO 读不到，或 DTO 写不进）。两个方向都要锁。
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
        // 关键：留空必须原样透传为 null。MyBatis-Plus 会忽略 null 字段 ——
        // 若这里补上默认值，任何一次不携带这两个字段的更新都会把管理员的选择重置回默认。
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

    private static CreateInstanceDTO toCreateDto(Boolean console, Boolean widget) {
        CreateInstanceDTO dto = new CreateInstanceDTO();
        dto.setName("Headhunter Assist");
        dto.setAllowConsoleSession(console);
        dto.setAllowWidgetSession(widget);
        return dto;
    }
}
