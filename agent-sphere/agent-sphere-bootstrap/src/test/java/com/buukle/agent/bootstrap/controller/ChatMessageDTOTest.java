package com.buukle.agent.bootstrap.controller;

import com.buukle.agent.model.dtvo.dto.complete.ChatMessageDTO;
import com.buukle.agent.model.dtvo.dto.complete.ChatMessagePartDTO;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatMessageDTOTest {

    ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
    }

    @Test
    void textOnly_contentSerializesAsString() throws Exception {
        ChatMessageDTO msg = new ChatMessageDTO()
                .setRole("user")
                .setContent("你好");

        String json = objectMapper.writeValueAsString(msg);

        assertTrue(json.contains("\"role\":\"user\""));
        assertTrue(json.contains("\"content\":\"你好\""));
        assertTrue(!json.contains("image_url"));
    }

    @Test
    void withImageParts_serializesAsPartsArray() throws Exception {
        ChatMessagePartDTO.ImageUrl imageUrl = new ChatMessagePartDTO.ImageUrl()
                .setUrl("data:image/png;base64,AAAA");
        ChatMessageDTO msg = new ChatMessageDTO()
                .setRole("user")
                .setContent(List.of(
                        new ChatMessagePartDTO().setType(ChatMessagePartDTO.TYPE_TEXT).setText("看图"),
                        new ChatMessagePartDTO()
                                .setType(ChatMessagePartDTO.TYPE_IMAGE_URL)
                                .setImageUrl(imageUrl)));

        String json = objectMapper.writeValueAsString(msg);

        assertTrue(json.contains("\"content\""));
        assertTrue(json.contains("\"type\":\"text\""));
        assertTrue(json.contains("\"type\":\"image_url\""));
        assertTrue(json.contains("\"image_url\""));
        assertTrue(json.contains("\"url\":\"data:image/png;base64,AAAA\""));
    }

    @Test
    void partsArray_deserializesAsRawMaps() throws Exception {
        // content 字段无类型信息：反序列化时 parts 元素回落为通用 Map（消息仅朝外发送，读侧不依赖它）
        String json = "{\"role\":\"user\",\"content\":["
                + "{\"type\":\"text\",\"text\":\"看图\"},"
                + "{\"type\":\"image_url\",\"image_url\":{\"url\":\"data:image/png;base64,AAAA\"}}]}";

        ChatMessageDTO msg = objectMapper.readValue(json, ChatMessageDTO.class);

        assertTrue(msg.getContent() instanceof List<?>);
        List<?> parts = (List<?>) msg.getContent();
        assertEquals(2, parts.size());
        java.util.Map<?, ?> image = (java.util.Map<?, ?>) parts.get(1);
        assertEquals("image_url", image.get("type"));
        assertEquals("data:image/png;base64,AAAA",
                ((java.util.Map<?, ?>) image.get("image_url")).get("url"));
    }

    @Test
    void textOnly_deserializesAsString() throws Exception {
        ChatMessageDTO msg = objectMapper.readValue(
                "{\"role\":\"user\",\"content\":\"你好\"}", ChatMessageDTO.class);

        assertEquals("你好", msg.getContent());
    }
}