package com.buukle.agent.bootstrap.controller;

import com.buukle.agent.sso.controller.SsoIdentityController;
import com.buukle.agent.sso.dtvo.vo.SsoIdentityVO;
import com.buukle.agent.sso.service.SsoService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class SsoIdentityControllerTest {

    MockMvc mockMvc;

    @Mock
    SsoService ssoService;

    @InjectMocks
    SsoIdentityController ssoIdentityController;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(ssoIdentityController).build();
    }

    @Test
    void me_withSsoIdentity_shouldReturnProviderAndSubject() throws Exception {
        SsoIdentityVO vo = new SsoIdentityVO();
        vo.setProviderCode("bole");
        vo.setSubject("elvin");
        given(ssoService.getCurrentIdentity()).willReturn(vo);

        mockMvc.perform(get("/api/v1/sso/me"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.providerCode").value("bole"))
                .andExpect(jsonPath("$.subject").value("elvin"));
    }

    @Test
    void me_withoutSsoIdentity_shouldReturnNull() throws Exception {
        given(ssoService.getCurrentIdentity()).willReturn(null);

        mockMvc.perform(get("/api/v1/sso/me"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").doesNotExist());
    }
}
