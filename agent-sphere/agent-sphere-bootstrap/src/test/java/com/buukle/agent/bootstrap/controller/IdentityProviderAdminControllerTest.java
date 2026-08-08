package com.buukle.agent.bootstrap.controller;

import com.buukle.agent.common.exception.GlobalExceptionHandler;
import com.buukle.agent.resource.template.ResourceTemplates;
import com.buukle.agent.sso.controller.IdentityProviderAdminController;
import com.buukle.agent.sso.dtvo.vo.ResourceTemplateVO;
import com.buukle.agent.sso.dtvo.vo.IdentityProviderVO;
import com.buukle.agent.sso.service.IdentityProviderService;
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

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class IdentityProviderAdminControllerTest {

    MockMvc mockMvc;
    ObjectMapper objectMapper = new ObjectMapper();

    @Mock
    IdentityProviderService identityProviderService;

    @InjectMocks
    IdentityProviderAdminController controller;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    private IdentityProviderVO providerVO(Long id, String code, String name) {
        IdentityProviderVO vo = new IdentityProviderVO();
        vo.setId(id);
        vo.setCode(code);
        vo.setType("OIDC");
        vo.setName(name);
        vo.setIssuer("https://idp.example.com");
        vo.setClientId("client-1");
        vo.setHasSecret(true);
        vo.setClientSecret("****");
        vo.setAuthorizationEndpoint("https://idp.example.com/oauth2/authorize");
        vo.setTokenEndpoint("https://idp.example.com/oauth2/token");
        vo.setJwksUrl("https://idp.example.com/jwks");
        vo.setEnabled(true);
        vo.setStatus("ACTIVE");
        return vo;
    }

    @Test
    void create_shouldReturnCreatedProvider() throws Exception {
        IdentityProviderVO vo = providerVO(1L, "business", "业务身份源");
        given(identityProviderService.createProvider(any())).willReturn(vo);

        mockMvc.perform(post("/api/v1/admin/identity-providers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"code":"business","name":"业务身份源","issuer":"https://idp.example.com",
                                 "clientId":"client-1","clientSecret":"secret-1",
                                 "authorizationEndpoint":"https://idp.example.com/oauth2/authorize",
                                 "tokenEndpoint":"https://idp.example.com/oauth2/token",
                                 "jwksUrl":"https://idp.example.com/jwks"}"
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.code").value("business"))
                .andExpect(jsonPath("$.clientSecret").value("****"))
                .andExpect(jsonPath("$.hasSecret").value(true));
    }

    @Test
    void list_shouldReturnProviders() throws Exception {
        given(identityProviderService.listProviders(eq("business")))
                .willReturn(List.of(providerVO(1L, "business", "业务身份源")));

        mockMvc.perform(get("/api/v1/admin/identity-providers").param("keyword", "business"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].code").value("business"))
                .andExpect(jsonPath("$[0].clientSecret").value("****"));
    }

    @Test
    void get_shouldReturnProvider() throws Exception {
        given(identityProviderService.getProvider(eq(1L)))
                .willReturn(providerVO(1L, "business", "业务身份源"));

        mockMvc.perform(get("/api/v1/admin/identity-providers/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.hasSecret").value(true));
    }

    @Test
    void update_shouldReturnUpdatedProvider() throws Exception {
        IdentityProviderVO vo = providerVO(1L, "business", "业务身份源-新");
        given(identityProviderService.updateProvider(eq(1L), any())).willReturn(vo);

        mockMvc.perform(put("/api/v1/admin/identity-providers/1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"业务身份源-新","issuer":"https://idp.example.com",
                                 "clientId":"client-1",
                                 "authorizationEndpoint":"https://idp.example.com/oauth2/authorize",
                                 "tokenEndpoint":"https://idp.example.com/oauth2/token",
                                 "jwksUrl":"https://idp.example.com/jwks"}"
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("业务身份源-新"));
    }

    @Test
    void delete_shouldReturnOk() throws Exception {
        doNothing().when(identityProviderService).deleteProvider(eq(1L));

        mockMvc.perform(delete("/api/v1/admin/identity-providers/1"))
                .andExpect(status().isOk());
        verify(identityProviderService).deleteProvider(1L);
    }

    @Test
    void setEnabled_shouldReturnOk() throws Exception {
        doNothing().when(identityProviderService).setEnabled(eq(1L), eq(false));

        mockMvc.perform(put("/api/v1/admin/identity-providers/1/enabled")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":false}"))
                .andExpect(status().isOk());
        verify(identityProviderService).setEnabled(1L, false);
    }

    @Test
    void testConnection_shouldReturnOk() throws Exception {
        doNothing().when(identityProviderService).testConnection(eq(1L));

        mockMvc.perform(post("/api/v1/admin/identity-providers/1/test"))
                .andExpect(status().isOk());
        verify(identityProviderService).testConnection(1L);
    }

    @Test
    void getDefaultResourceTemplate_shouldReturnSystemDefault() throws Exception {
        given(identityProviderService.getDefaultResourceTemplate())
                .willReturn(new ResourceTemplateVO(ResourceTemplates.DEFAULT));

        mockMvc.perform(get("/api/v1/admin/identity-providers/resource-template-default"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.template").value(ResourceTemplates.DEFAULT));
        verify(identityProviderService).getDefaultResourceTemplate();
    }

    @Test
    void create_shouldRejectBlankFields() throws Exception {
        mockMvc.perform(post("/api/v1/admin/identity-providers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"\"}"))
                .andExpect(status().isBadRequest());
        verify(identityProviderService, org.mockito.Mockito.never()).createProvider(any());
    }
}