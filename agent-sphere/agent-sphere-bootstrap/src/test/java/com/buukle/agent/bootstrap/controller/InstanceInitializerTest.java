package com.buukle.agent.bootstrap.controller;

import com.buukle.agent.capability.builtin.dtvo.enums.BuiltinToolEnum;
import com.buukle.agent.capability.builtin.dtvo.vo.BuiltinToolVO;
import com.buukle.agent.capability.builtin.spi.CapabilityBuiltinSpi;
import com.buukle.agent.instance.dtvo.dto.CreateInstanceCapabilityDTO;
import com.buukle.agent.instance.dtvo.dto.CreateInstanceDTO;
import com.buukle.agent.instance.dtvo.enums.InstanceCapabilityEnum;
import com.buukle.agent.instance.dtvo.vo.InstanceVO;
import com.buukle.agent.instance.spi.InstanceCapabilitySpi;
import com.buukle.agent.instance.spi.InstanceSpi;
import com.buukle.agent.instance.service.InstanceService;
import com.buukle.agent.resource.template.ResourceInitContext;
import com.buukle.agent.resource.template.init.InstanceInitializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class InstanceInitializerTest {

    @Mock
    InstanceSpi instanceSpi;
    @Mock
    InstanceService instanceService;
    @Mock
    CapabilityBuiltinSpi capabilityBuiltinSpi;
    @Mock
    InstanceCapabilitySpi instanceCapabilitySpi;

    InstanceInitializer initializer;
    final ObjectMapper mapper = new ObjectMapper();
    final ResourceInitContext ctx = new ResourceInitContext("business", "业务身份源", "business_7");

    @BeforeEach
    void setUp() {
        initializer = new InstanceInitializer(instanceSpi, instanceService, capabilityBuiltinSpi, instanceCapabilitySpi);
    }

    private InstanceVO instanceVO(Long id) {
        InstanceVO vo = new InstanceVO();
        vo.setId(id);
        vo.setName("招聘助手");
        return vo;
    }

    private BuiltinToolVO tool(Long id) {
        BuiltinToolVO vo = new BuiltinToolVO();
        vo.setId(id);
        vo.setName("builtin.CapabilityBuiltinToolChrome");
        return vo;
    }

    private JsonNode descriptor() throws Exception {
        return mapper.readTree("{\"type\":\"instance\",\"name\":\"招聘助手\",\"businessType\":\"sourcing\",\"route\":\"deepseek-v4-flash\"}");
    }

    @Test
    void initialize_shouldBindBuiltinChromeToolToInstance() throws Exception {
        when(instanceSpi.listInstances(anyString(), any(), any())).thenReturn(List.of());
        when(instanceService.createInstance(any(CreateInstanceDTO.class))).thenReturn(instanceVO(10L));
        when(capabilityBuiltinSpi.listBuiltinTools())
                .thenReturn(List.of(tool(2L), tool((long) BuiltinToolEnum.CHROME.getId())));

        initializer.initialize(descriptor(), ctx);

        ArgumentCaptor<CreateInstanceCapabilityDTO> cap = ArgumentCaptor.forClass(CreateInstanceCapabilityDTO.class);
        verify(instanceCapabilitySpi).createCapability(cap.capture());
        assertEquals(10L, cap.getValue().getInstanceId());
        assertEquals(InstanceCapabilityEnum.CAPABILITY_TYPE_BUILTIN, cap.getValue().getCapabilityType());
        assertEquals((long) BuiltinToolEnum.CHROME.getId(), cap.getValue().getCapabilityId());
        assertEquals(InstanceCapabilityEnum.STATUS_ENABLED, cap.getValue().getStatus());
    }

    @Test
    void initialize_shouldSkipBindingWhenChromeToolMissing() throws Exception {
        when(instanceSpi.listInstances(anyString(), any(), any())).thenReturn(List.of());
        when(instanceService.createInstance(any(CreateInstanceDTO.class))).thenReturn(instanceVO(10L));
        when(capabilityBuiltinSpi.listBuiltinTools()).thenReturn(List.of(tool(2L)));

        initializer.initialize(descriptor(), ctx);

        verify(instanceCapabilitySpi, never()).createCapability(any(CreateInstanceCapabilityDTO.class));
    }

    @Test
    void initialize_shouldNotBindWhenInstanceExists() throws Exception {
        when(instanceSpi.listInstances(anyString(), any(), any())).thenReturn(List.of(instanceVO(99L)));

        try {
            initializer.initialize(descriptor(), ctx);
        } catch (RuntimeException ignored) {
            // ResourceExistsException
        }

        verify(instanceService, never()).createInstance(any(CreateInstanceDTO.class));
        verify(instanceCapabilitySpi, never()).createCapability(any(CreateInstanceCapabilityDTO.class));
    }
}
