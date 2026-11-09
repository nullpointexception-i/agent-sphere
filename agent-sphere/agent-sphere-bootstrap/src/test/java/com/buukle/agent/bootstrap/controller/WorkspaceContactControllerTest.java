package com.buukle.agent.bootstrap.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.buukle.agent.instance.controller.WorkspaceContactController;
import com.buukle.agent.instance.dtvo.vo.WorkspaceContactVO;
import com.buukle.agent.instance.spi.UserSpi;
import com.buukle.agent.sso.spi.SsoIdentitySpi;
import com.buukle.agent.sso.spi.SsoUserLabelVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Map;

import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Workspace contacts must carry the same provider@username label as the console. */
@ExtendWith(MockitoExtension.class)
class WorkspaceContactControllerTest {

    @Mock
    UserSpi userSpi;
    @Mock
    SsoIdentitySpi ssoIdentitySpi;

    MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(
                new WorkspaceContactController(userSpi, ssoIdentitySpi)).build();
    }

    @Test
    void users_attachesSsoLabel() throws Exception {
        WorkspaceContactVO contact = new WorkspaceContactVO();
        contact.setId(2L);
        contact.setUsername("bole_2");
        contact.setDisplayName("Bob");
        Page<WorkspaceContactVO> page = new Page<>(1, 20, 1);
        page.setRecords(List.of(contact));
        given(userSpi.pageContacts(1, 20, "b")).willReturn(page);
        given(ssoIdentitySpi.labelsByUserIds(List.of(2L)))
                .willReturn(Map.of(2L, new SsoUserLabelVO(2L, "bole", "elvin")));

        mockMvc.perform(get("/api/v1/workspace/contacts/users").param("keyword", "b"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.records[0].displayName").value("bole@elvin"))
                .andExpect(jsonPath("$.records[0].ssoProviderCode").value("bole"))
                .andExpect(jsonPath("$.records[0].ssoSubject").value("elvin"));
    }
}
