package com.buukle.agent.bootstrap.controller;

import com.buukle.agent.common.exception.BizException;
import com.buukle.agent.instance.dtvo.dto.SendMessageDTO;
import com.buukle.agent.runtime.orchestration.controller.ChatRuntimeController;
import com.buukle.agent.runtime.orchestration.dtvo.vo.ChatMessageResponseVO;
import com.buukle.agent.runtime.orchestration.service.ChatRuntimeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class ChatRuntimeControllerTest {

    MockMvc mockMvc;

    @Mock
    ChatRuntimeService chatRuntimeService;

    @InjectMocks
    ChatRuntimeController controller;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    void chat_imageOnly_sendsWithoutText() throws Exception {
        ChatMessageResponseVO vo = new ChatMessageResponseVO();
        vo.setRunId(10L);
        vo.setStatus("processing");
        given(chatRuntimeService.chat(eq(1L), any(SendMessageDTO.class))).willReturn(vo);

        mockMvc.perform(post("/api/v1/runtime/1/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"\",\"attachmentKeys\":[\"k1\",\"k2\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.runId").value(10));
    }

    @Test
    void chat_textOnly_sendsNormally() throws Exception {
        ChatMessageResponseVO vo = new ChatMessageResponseVO();
        vo.setRunId(11L);
        vo.setStatus("processing");
        given(chatRuntimeService.chat(eq(1L), any(SendMessageDTO.class))).willReturn(vo);

        mockMvc.perform(post("/api/v1/runtime/1/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"你好\",\"attachmentKeys\":null}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.runId").value(11));
    }

    @Test
    void serviceGuard_bothMessageAndAttachmentEmpty_rejected() throws Exception {
        Method m = ChatRuntimeService.class.getDeclaredMethod("assertMessageOrAttachment", SendMessageDTO.class);
        m.setAccessible(true);

        SendMessageDTO bothEmpty = new SendMessageDTO();
        bothEmpty.setMessage("");
        bothEmpty.setAttachmentKeys(List.of());
        assertThrows(BizException.class, () -> invokedBiz(m, bothEmpty));
    }

    @Test
    void serviceGuard_nullMessageAndEmptyAttachment_rejected() throws Exception {
        Method m = ChatRuntimeService.class.getDeclaredMethod("assertMessageOrAttachment", SendMessageDTO.class);
        m.setAccessible(true);

        SendMessageDTO dto = new SendMessageDTO();
        dto.setMessage(null);
        dto.setAttachmentKeys(null);
        assertThrows(BizException.class, () -> invokedBiz(m, dto));
    }

    @Test
    void serviceGuard_imageOnly_allowed() throws Exception {
        Method m = ChatRuntimeService.class.getDeclaredMethod("assertMessageOrAttachment", SendMessageDTO.class);
        m.setAccessible(true);

        SendMessageDTO dto = new SendMessageDTO();
        dto.setMessage("");
        dto.setAttachmentKeys(List.of("k1"));
        m.invoke(null, dto); // 不抛异常即通过
    }

    private static void invokedBiz(Method m, SendMessageDTO dto) throws Throwable {
        try {
            m.invoke(null, dto);
        } catch (java.lang.reflect.InvocationTargetException e) {
            throw e.getCause();
        }
    }
}