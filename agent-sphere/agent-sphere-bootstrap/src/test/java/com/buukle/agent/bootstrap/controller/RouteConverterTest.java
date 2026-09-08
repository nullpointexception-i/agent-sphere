package com.buukle.agent.bootstrap.controller;

import com.buukle.agent.model.domain.AgentModelRoute;
import com.buukle.agent.model.dtvo.dto.CreateRouteDTO;
import com.buukle.agent.model.dtvo.vo.ModelRouteVO;
import com.buukle.agent.model.service.converter.RouteConverter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RouteConverterTest {

    RouteConverter converter;

    @BeforeEach
    void setUp() {
        converter = new RouteConverter();
    }

    @Test
    void toVO_mapsSupportsAttachment() {
        AgentModelRoute route = new AgentModelRoute();
        route.setId(1L);
        route.setModelName("gpt-4o");
        route.setSupportsAttachment(true);

        ModelRouteVO vo = converter.toVO(route);

        assertNotNull(vo);
        assertTrue(vo.getSupportsAttachment());
    }

    @Test
    void toVO_supportsAttachmentNull_passthrough() {
        AgentModelRoute route = new AgentModelRoute();
        route.setModelName("gpt-4o");

        ModelRouteVO vo = converter.toVO(route);

        assertNull(vo.getSupportsAttachment());
    }

    @Test
    void toVO_nullRoute_returnsNull() {
        assertNull(converter.toVO(null));
    }

    @Test
    void toDO_createTrue_setsSupportsAttachment() {
        CreateRouteDTO dto = new CreateRouteDTO();
        dto.setProviderId(7L);
        dto.setModelName("deepseek-vl");
        dto.setSupportsAttachment(true);

        AgentModelRoute route = converter.toDO(dto);

        assertTrue(route.getSupportsAttachment());
    }

    @Test
    void toDO_updateFalse_setsFalse() {
        CreateRouteDTO dto = new CreateRouteDTO();
        dto.setProviderId(7L);
        dto.setModelName("deepseek-chat");
        dto.setSupportsAttachment(false);

        AgentModelRoute route = converter.toDO(dto);

        assertFalse(route.getSupportsAttachment());
    }

    @Test
    void toDO_nullSupportsAttachment_keepsDefault() {
        CreateRouteDTO dto = new CreateRouteDTO();
        dto.setProviderId(7L);
        dto.setModelName("deepseek-chat");

        AgentModelRoute route = converter.toDO(dto);

        // 更新场景：未传该字段不得覆盖既有值（默认 null，走更新时不 set）
        assertNull(route.getSupportsAttachment());
    }
}