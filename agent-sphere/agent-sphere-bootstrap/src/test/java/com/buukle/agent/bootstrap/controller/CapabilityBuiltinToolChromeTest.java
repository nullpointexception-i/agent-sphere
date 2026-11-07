package com.buukle.agent.bootstrap.controller;

import com.buukle.agent.capability.builtin.tool.chrome.CapabilityBuiltinToolChrome;
import com.buukle.agent.capability.builtin.tool.chrome.dtvo.dto.ChromeExecuteContext;
import com.buukle.agent.capability.builtin.tool.chrome.dtvo.vo.ChromeResultVO;
import com.buukle.agent.common.chrome.ChromeCallbackDTO;
import com.buukle.agent.common.chrome.ChromeCommandDTO;
import com.buukle.agent.common.chrome.ChromePendingStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class CapabilityBuiltinToolChromeTest {

    private CapabilityBuiltinToolChrome tool;
    private ApplicationEventPublisher publisher;
    private Deque<ChromeCallbackDTO> callbacks;

    @BeforeEach
    void setUp() {
        tool = new CapabilityBuiltinToolChrome();
        publisher = mock(ApplicationEventPublisher.class);
        ReflectionTestUtils.setField(tool, "eventPublisher", publisher);
        callbacks = new ArrayDeque<>();
        doAnswer(inv -> {
            ChromeCommandDTO cmd = inv.getArgument(0);
            ChromeCallbackDTO result = callbacks.poll();
            // The tool puts the future into ChromePendingStore only after publishEvent:
            // complete asynchronously (20ms delay) so the put happens before complete.
            new Thread(() -> {
                try {
                    Thread.sleep(20);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
                ChromePendingStore.complete(cmd.getCommandId(), result);
            }, "chrome-callback-stub").start();
            return null;
        }).when(publisher).publishEvent(any(ChromeCommandDTO.class));
    }

    private ChromeExecuteContext exec(String action, String selector) {
        ChromeExecuteContext cec = new ChromeExecuteContext();
        cec.setSessionId(1L);
        cec.setAction(action);
        cec.setSelector(selector);
        return cec;
    }

    private static ChromeCallbackDTO fail(String category) {
        ChromeCallbackDTO cb = ChromeCallbackDTO.fail("cmd", "boom");
        cb.setErrorCategory(category);
        return cb;
    }

    private ChromeExecuteContext execJs(String code) {
        ChromeExecuteContext cec = exec("executeJS", null);
        cec.setCode(code);
        return cec;
    }

    /**
     * executeJS read-only gate: all navigation writes must be rejected at the **tool layer** without **sending any command**.
     *
     * <p>This directly counters production risk-control triggers — script-driven navigation (especially location.href and history.back)
     * bypasses the real click path and is a major reason some sites flag the session as abnormal and serve a risk-control challenge page.
     */
    @Test
    void executeJs_navigationWrites_allBlockedWithoutPublish() {
        String[] codes = {
                "location.href='https://host.example/resume/showresumedetail/?res_id_encode=X'",
                "location['href']='https://host.example/x'",
                "location.assign('https://host.example/x')",
                "location.replace('https://host.example/x')",
                "location.reload()",
                "document.location='https://host.example/x'",
                "window.location.href='https://host.example/x'",
                "top.location='https://host.example/x'",
                "history.back()",
                "history.pushState({}, '', '/x')",
                "window.open('https://host.example/x')",
                "document.forms[0].submit()",
                "location.hash = '#a'",
        };
        for (String code : codes) {
            ChromeResultVO vo = (ChromeResultVO) tool.execute(execJs(code));
            assertFalse(vo.isSuccess(), "应被拒: " + code);
            assertEquals("js_navigation_blocked", vo.getErrorCategory(), "错误类别: " + code);
        }
        // No command may be dispatched at all (the only browser command exit is buildCommand)
        verify(publisher, never()).publishEvent(any(ChromeCommandDTO.class));
    }

    /** Read-only code must pass — normal reads (reading location properties, or == comparisons) must not be mistaken for navigation writes. */
    @Test
    void executeJs_readOnly_allowed() {
        String[] okCodes = {
                "return document.title",
                "return document.querySelectorAll('.detail-resume-card-wrap').length",
                "const p = location.pathname; return p",
                "return location.href === 'x'",
                "return document.querySelector('a[href*=detail]')?.getAttribute('href') || ''",
        };
        for (String code : okCodes) {
            callbacks.add(ChromeCallbackDTO.ok("cmd", "ok"));
            ChromeResultVO vo = (ChromeResultVO) tool.execute(execJs(code));
            assertTrue(vo.isSuccess(), "应放行: " + code);
        }
        verify(publisher, org.mockito.Mockito.times(okCodes.length))
                .publishEvent(any(ChromeCommandDTO.class));
    }

    @Test
    void repeatedSameFailure_guardAfterTwo() {
        for (int i = 0; i < 2; i++) {
            callbacks.add(fail("not_found"));
            ChromeResultVO vo = (ChromeResultVO) tool.execute(exec("click", ".degree-item"));
            assertFalse(vo.isSuccess());
            assertEquals("not_found", vo.getErrorCategory());
        }
        // Third attempt: no further command is published, straight to repeated_failure (guard moved up: stop after 2 consecutive failures)
        callbacks.add(fail("not_found"));
        ChromeResultVO vo = (ChromeResultVO) tool.execute(exec("click", ".degree-item"));
        assertFalse(vo.isSuccess());
        assertEquals("repeated_failure", vo.getErrorCategory());
        verify(publisher, org.mockito.Mockito.times(2)).publishEvent(any(ChromeCommandDTO.class));
    }

    @Test
    void successResetsConsecutiveFailureCounter() {
        callbacks.add(fail("not_found"));
        assertFalse(((ChromeResultVO) tool.execute(exec("click", ".a"))).isSuccess());
        callbacks.add(ChromeCallbackDTO.ok("cmd", "ok"));
        assertTrue(((ChromeResultVO) tool.execute(exec("click", ".a"))).isSuccess());
        callbacks.add(fail("not_found"));
        assertFalse(((ChromeResultVO) tool.execute(exec("click", ".a"))).isSuccess());
        // After success resets the count, a single later failure still records only not_found (guard is 2 failures; 1 does not trigger)
        callbacks.add(ChromeCallbackDTO.ok("cmd", "ok"));
        assertTrue(((ChromeResultVO) tool.execute(exec("click", ".a"))).isSuccess());
        callbacks.add(fail("not_found"));
        ChromeResultVO vo = (ChromeResultVO) tool.execute(exec("click", ".a"));
        assertEquals("not_found", vo.getErrorCategory());
    }

    @Test
    void injectFailed_returnsImmediatelyWithoutRetry() {
        // The tool layer no longer auto-resends on inject_failed: send one command and report the injection failure as-is.
        // The second (ok) callback is left on purpose: if auto-resend still existed, it would be consumed and turn the result into success.
        callbacks.add(fail("inject_failed"));
        callbacks.add(ChromeCallbackDTO.ok("cmd", "should-not-be-used"));

        ChromeResultVO vo = (ChromeResultVO) tool.execute(exec("getContent", ".x"));

        assertFalse(vo.isSuccess());
        assertEquals("inject_failed", vo.getErrorCategory());
        verify(publisher, org.mockito.Mockito.times(1)).publishEvent(any(ChromeCommandDTO.class));
    }

    @Test
    void frameIdGreaterThanZero_rejectedWithoutPublish() {
        ChromeExecuteContext cec = exec("click", ".degree-item");
        cec.setFrameId(2);

        ChromeResultVO vo = (ChromeResultVO) tool.execute(cec);

        assertFalse(vo.isSuccess());
        assertTrue(vo.getErrorMessage().contains("frameId"));
        verify(publisher, never()).publishEvent(any(ChromeCommandDTO.class));
    }

    @Test
    void callbackArrivingDuringPublishIsNotLost() {
        ChromeCallbackDTO expected = ChromeCallbackDTO.ok("cmd", "immediate");
        doAnswer(inv -> {
            ChromeCommandDTO cmd = inv.getArgument(0);
            ChromePendingStore.complete(cmd.getCommandId(), expected);
            return null;
        }).when(publisher).publishEvent(any(ChromeCommandDTO.class));

        ChromeResultVO vo = (ChromeResultVO) tool.execute(exec("click", ".ready"));

        assertTrue(vo.isSuccess());
        assertEquals("immediate", vo.getData());
    }

    @Test
    void timeoutHasStructuredCategory() {
        doNothing().when(publisher).publishEvent(any(ChromeCommandDTO.class));
        ChromeExecuteContext cec = exec("click", ".slow");
        cec.setTimeout(2);

        ChromeResultVO vo = (ChromeResultVO) tool.execute(cec);

        assertFalse(vo.isSuccess());
        assertEquals("timeout", vo.getErrorCategory());
        assertTrue(vo.getWarning() != null && vo.getWarning().contains("getContent first"));
    }

    @Test
    void timeoutDoesNotAdvanceRepeatedFailureCounter() {
        AtomicBoolean silent = new AtomicBoolean(false);
        doAnswer(inv -> {
            if (silent.get()) return null; // Never complete the future → trigger a timeout
            ChromeCommandDTO cmd = inv.getArgument(0);
            ChromeCallbackDTO result = callbacks.poll();
            new Thread(() -> {
                try {
                    Thread.sleep(20);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
                ChromePendingStore.complete(cmd.getCommandId(), result);
            }, "chrome-callback-stub").start();
            return null;
        }).when(publisher).publishEvent(any(ChromeCommandDTO.class));

        // Two timeouts: on slow pages the action may have taken effect, so they must not count as consecutive failures
        silent.set(true);
        for (int i = 0; i < 2; i++) {
            ChromeExecuteContext t = exec("click", ".slow-city");
            t.setTimeout(2);
            assertFalse(((ChromeResultVO) tool.execute(t)).isSuccess());
        }
        // Subsequent real failures hit repeated_failure at 2 (timeouts excluded; verifies the moved-up guard)
        silent.set(false);
        for (int i = 0; i < 3; i++) {
            callbacks.add(fail("not_found"));
            assertFalse(((ChromeResultVO) tool.execute(exec("click", ".slow-city"))).isSuccess());
        }
        ChromeResultVO vo = (ChromeResultVO) tool.execute(exec("click", ".slow-city"));
        assertEquals("repeated_failure", vo.getErrorCategory());
    }
}
