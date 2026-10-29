package com.buukle.agent.bootstrap.controller;

import com.buukle.agent.capability.builtin.dtvo.enums.BuiltinToolEnum;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BuiltinToolPolicyTest {

    @Test
    void chromeIsMixedL1AndCompressed() {
        BuiltinToolEnum chrome = BuiltinToolEnum.CHROME;
        assertEquals("READ_WRITE", chrome.getAccessMode().name());
        assertEquals("L1", chrome.getSecurityLevel().name());
        assertTrue(chrome.isCompressEnabled());
    }

    @Test
    void writeToolsUseExpectedSecurity() {
        assertEquals("WRITE", BuiltinToolEnum.DOCWRITE.getAccessMode().name());
        assertEquals("L2", BuiltinToolEnum.DOCWRITE.getSecurityLevel().name());
        assertEquals("READ", BuiltinToolEnum.DOC_READ.getAccessMode().name());
        assertEquals("L1", BuiltinToolEnum.DOC_READ.getSecurityLevel().name());
        assertEquals("WRITE", BuiltinToolEnum.SKILL_MANAGE.getAccessMode().name());
        assertEquals("L2", BuiltinToolEnum.SKILL_MANAGE.getSecurityLevel().name());
        assertFalse(BuiltinToolEnum.SKILL_LIBRARY.isCompressEnabled());
    }
}
