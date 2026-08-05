package com.buukle.agent.bootstrap.controller;

import com.buukle.agent.instance.dtvo.vo.UserVO;
import com.buukle.agent.sso.controller.SsoAuthController;
import com.buukle.agent.sso.dtvo.SsoAuthorizeVO;
import com.buukle.agent.sso.dtvo.SsoExchangeDTO;
import com.buukle.agent.sso.service.SsoService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class SsoAuthControllerTest {

    MockMvc mockMvc;
    ObjectMapper objectMapper = new ObjectMapper();

    @Mock
    SsoService ssoService;

    @InjectMocks
    SsoAuthController ssoAuthController;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(ssoAuthController).build();
    }

    @Test
    void authorize_shouldReturnAuthorizeUrl() throws Exception {
        SsoAuthorizeVO vo = new SsoAuthorizeVO();
        vo.setProvider("business");
        vo.setState("state-1");
        vo.setAuthorizeUrl("https://idp.example.com/oauth2/authorize?state=state-1");
        given(ssoService.authorize(eq("business"), eq(null), eq(null))).willReturn(vo);

        mockMvc.perform(get("/api/v1/auth/sso/authorize").param("provider", "business"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.provider").value("business"))
                .andExpect(jsonPath("$.state").value("state-1"))
                .andExpect(jsonPath("$.authorizeUrl").value("https://idp.example.com/oauth2/authorize?state=state-1"));
    }

    @Test
    void callback_shouldRedirectWithOtc() throws Exception {
        given(ssoService.callback(eq("code-1"), eq("state-1"), eq(null), eq(null)))
                .willReturn("http://localhost:8000/user/login?otc=otc-1");

        mockMvc.perform(get("/api/v1/auth/sso/callback")
                        .param("code", "code-1")
                        .param("state", "state-1"))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl("http://localhost:8000/user/login?otc=otc-1"));
    }

    @Test
    void exchange_shouldReturnUserWithToken() throws Exception {
        UserVO user = new UserVO();
        user.setId(1L);
        user.setUsername("business_bob");
        user.setToken("token-abc");
        given(ssoService.exchange(anyString())).willReturn(user);

        SsoExchangeDTO dto = new SsoExchangeDTO();
        dto.setOtc("otc-1");
        mockMvc.perform(post("/api/v1/auth/sso/exchange")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(dto)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1L))
                .andExpect(jsonPath("$.username").value("business_bob"))
                .andExpect(jsonPath("$.token").value("token-abc"));

        verify(ssoService).exchange("otc-1");
    }
}