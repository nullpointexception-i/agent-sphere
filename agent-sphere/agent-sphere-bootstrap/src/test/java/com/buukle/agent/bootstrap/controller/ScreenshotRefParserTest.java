package com.buukle.agent.bootstrap.controller;

import com.buukle.agent.instance.dtvo.vo.RunAttachment;
import com.buukle.agent.instance.service.util.ScreenshotRefParser;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ScreenshotRefParserTest {

    @Test
    void extract_withScreenshot_returnsRef() {
        List<RunAttachment> list = ScreenshotRefParser.extractScreenshotImages(
                "{\"success\":true,\"data\":{\"screenshot\":{\"fileKey\":\"abc-123\",\"contentType\":\"image/jpeg\"}}}");

        assertEquals(1, list.size());
        assertEquals(new RunAttachment("abc-123", "image/jpeg"), list.get(0));
    }

    @Test
    void extract_withoutScreenshot_returnsEmpty() {
        assertEquals(0, ScreenshotRefParser.extractScreenshotImages(
                "{\"success\":true,\"data\":{\"click\":{\"ok\":true}}}").size());
        assertEquals(0, ScreenshotRefParser.extractScreenshotImages(null).size());
        assertEquals(0, ScreenshotRefParser.extractScreenshotImages("   ").size());
    }

    @Test
    void extract_missingFileKey_returnsEmpty() {
        assertEquals(0, ScreenshotRefParser.extractScreenshotImages(
                "{\"success\":true,\"data\":{\"screenshot\":{\"contentType\":\"image/jpeg\"}}}").size());
    }

    @Test
    void extract_invalidJson_returnsEmpty() {
        assertEquals(0, ScreenshotRefParser.extractScreenshotImages("not json").size());
    }

    @Test
    void extract_emptyContentType_fallsBack() {
        List<RunAttachment> list = ScreenshotRefParser.extractScreenshotImages(
                "{\"success\":true,\"data\":{\"screenshot\":{\"fileKey\":\"k-1\"}}}");

        assertEquals(1, list.size());
        assertEquals("application/octet-stream", list.get(0).contentType());
    }
}