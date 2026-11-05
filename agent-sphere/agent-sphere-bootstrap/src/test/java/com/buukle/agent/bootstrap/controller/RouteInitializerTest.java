package com.buukle.agent.bootstrap.controller;

import com.buukle.agent.model.dtvo.dto.CreateRouteDTO;
import com.buukle.agent.model.dtvo.vo.ModelRouteVO;
import com.buukle.agent.model.spi.RouteSpi;
import com.buukle.agent.resource.template.ResourceInitContext;
import com.buukle.agent.resource.template.init.RouteInitializer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RouteInitializerTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final ResourceInitContext ctx = new ResourceInitContext("deepseek", "DeepSeek", "alice");

    @Mock
    RouteSpi routeSpi;

    RouteInitializer initializer;

    @BeforeEach
    void setUp() {
        initializer = new RouteInitializer(routeSpi);
        ctx.put("model_provider", "DeepSeek", 1L);
        ctx.put("model_provider", "OrcaRouter", 2L);
    }

    @Test
    void defaultFlagComesFromDescriptorNotCreationOrder() throws Exception {
        when(routeSpi.listRoutesByProvider(any(), any())).thenReturn(List.of());
        ModelRouteVO orca = route(1L);
        ModelRouteVO deepseek = route(2L);
        when(routeSpi.createRoute(any())).thenReturn(orca, deepseek);

        initializer.initialize(descriptor("OrcaRouter", "openai/gpt-4o-mini", false), ctx);
        initializer.initialize(descriptor("DeepSeek", "deepseek-flash", true), ctx);

        ArgumentCaptor<CreateRouteDTO> created = ArgumentCaptor.forClass(CreateRouteDTO.class);
        verify(routeSpi, times(2)).createRoute(created.capture());
        assertFalse(created.getAllValues().get(0).getIsDefault());
        assertTrue(created.getAllValues().get(1).getIsDefault());
    }

    @Test
    void noDescriptorDefaultLeavesEveryRouteNonDefault() throws Exception {
        when(routeSpi.listRoutesByProvider(any(), any())).thenReturn(List.of());
        when(routeSpi.createRoute(any())).thenReturn(route(1L));

        initializer.initialize(descriptor("OrcaRouter", "openai/gpt-4o-mini", false), ctx);

        ArgumentCaptor<CreateRouteDTO> created = ArgumentCaptor.forClass(CreateRouteDTO.class);
        verify(routeSpi).createRoute(created.capture());
        assertFalse(created.getValue().getIsDefault());
    }

    private JsonNode descriptor(String provider, String modelName, boolean isDefault) throws Exception {
        return mapper.readTree("""
                {"type":"model_route","provider":"%s","modelName":"%s",
                 "company":"deepseek","weight":100,"default":%s}
                """.formatted(provider, modelName, isDefault));
    }

    private static ModelRouteVO route(Long id) {
        ModelRouteVO vo = new ModelRouteVO();
        vo.setId(id);
        return vo;
    }
}
