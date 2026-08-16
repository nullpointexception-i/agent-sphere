package com.buukle.agent.capability.builtin.tool.chrome;

import com.buukle.agent.capability.builtin.dtvo.enums.BuiltinToolEnum;
import com.buukle.agent.capability.builtin.tool.chrome.dtvo.dto.ChromeExecuteContext;
import com.buukle.agent.capability.builtin.tool.chrome.dtvo.vo.ChromeResultVO;
import com.buukle.agent.capability.builtin.tool.spi.CapabilityBuiltinToolSpi;
import com.buukle.agent.capability.builtin.tool.spi.constant.BuiltinToolConstants;
import com.buukle.agent.capability.builtin.tool.spi.dtvo.ExecuteContext;
import com.buukle.agent.capability.builtin.tool.spi.dtvo.ExecuteResult;
import com.buukle.agent.capability.builtin.tool.spi.dtvo.ToolInfoVO;
import com.buukle.agent.capability.builtin.tool.spi.util.ToolSchemaUtil;
import com.buukle.agent.common.chrome.ChromeCallbackDTO;
import com.buukle.agent.common.chrome.ChromeCommandDTO;
import com.buukle.agent.common.chrome.ChromePendingStore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

@Slf4j
@Component
public class CapabilityBuiltinToolChrome implements CapabilityBuiltinToolSpi {

    private static final String DESCRIPTION = "Chrome browser automation. Operations in priority order:\n"
            + "1. navigate — open a URL in the browser.\n"
            + "2. getContent(mode: snapshot) — BEST for discovering the page: returns an indexable list of visible interactive elements, each with a stable `ref` (0-based), role/name/tag/type/state/href. Also returns `domHash` — a fingerprint of the page. Reuse `ref` directly in click/type/hover/select/upload to avoid selector guessing.\n"
            + "   getContent(mode: summary) — structured summary (inputs/buttons/forms/navLinks/sections/dialogs). Icon-only buttons are reported by aria-label/title/svg-title/alt.\n"
            + "   getContent(mode: query, selector) — compact list of up to 50 matches for repeated elements (each with index).\n"
            + "   getContent(mode: extract, selector, fields) — structured extraction of repeated list blocks (e.g. candidate cards). `fields`: text (element text), .xxx (first sub-element text by CSS), @attr (attribute), href, value. Returns an array — ideal for reading N candidates/cards in ONE call instead of repeated full-DOM reads.\n"
            + "3. click(ref) or click(selector) or click(text) — click an element. Auto-waits up to ~3s for the element to appear and be interactable. Text click is scoped to VISIBLE clickable elements (hidden/CSS text ignored). Duplicate text across sections (e.g. several '确定'/'北京') is resolved with `occurrence` (Nth) and `scope` (restrict search to a container selector like [role=dialog]). Result includes `changed` (true if the page likely changed), `_clickable` (false = no real click target, treat as mis-click), `_clickedTag`/`class`, and `_hints` (dialogs/chips/count). If `changed:false` and no URL change, do NOT re-read the page — try another approach or wait.\n"
            + "4. type(selector|ref, text, append?, submit?) — fill input fields (works with SPA frameworks). When append=true, append instead of replace. When submit=true, presses Enter after typing (e.g. to fire a search); result includes `_submitted`/`changed`.\n"
            + "5. key(key) — send a keyboard key (Enter/Tab/Escape/Backspace/ArrowDown etc.) to the focused element (submit forms, close modals, shortcuts).\n"
            + "6. select(selector|ref, value|label) — choose an option in a <select>.\n"
            + "7. scroll(direction: up|down|left|right, amount?) or scroll(selector|ref) — scroll the page / to an element (infinite scroll, virtualized lists).\n"
            + "8. hover(selector/text) — reveal JS-driven hover menus.\n"
            + "9. upload(selector|ref, fileName, fileBase64, fileType?) — upload a file to an <input type=file> (works with drag-drop upload UIs).\n"
            + "10. wait(ms) or wait(selector|text|ref, timeout) — explicitly wait for dynamic content. Prefer `wait` over blind retries.\n"
            + "11. closeDialogs — close open modal dialogs/toasts.\n"
            + "12. executeJS — LAST RESORT (BLOCKED on strict-CSP sites e.g. 猎聘). If csp_blocked/detached, do NOT retry — re-read with getContent snapshot and use click/type.\n"
            + "13. Tab following: clicking a link opening a new tab auto-switches control (result includes _newTabId/_newTabUrl). Pass `tabId` to target a specific tab.\n"
            + "14. Per-command timeout: pass `timeout` (seconds, min 2) to override the default 10s for slow re-renders (default 30s for navigate).\n"
            + "Rules: prefer getContent(snapshot)+ref for everything. Use `_hints.dialogs` to know a modal opened and close it (click its 确定/取消 via scope). `_hints.count` shows result-count text (e.g. '共有 N 份简历') — filter counts may update asynchronously; wait() 1-2s before concluding a filter had no effect. If an action returns not_found/not_interactable, use wait() for a dynamic element or re-read the snapshot (refs may shift after DOM changes). Never blindly retry after csp_blocked/detached. If click returns changed:false, the page did not react — pick another approach.\n"
            + "When a strict filter combination returns 0 results, loosen filters stepwise (remove the most specific first) and evaluate candidates per-item against ALL criteria — do not settle for a zero-result dead end.\n"
            + "Results include `errorCategory` (not_found / csp_blocked / detached / inject_failed / timeout / no_tab / unknown) and `method`.";
    private static final long NAVIGATE_TIMEOUT_SECONDS = 30;
    private static final long ACTION_TIMEOUT_SECONDS = 10;

    private static final String ACTION_NAVIGATE = "navigate";
    private static final String ACTION_CLICK = "click";
    private static final String ACTION_TYPE = "type";

    private static final String ERR_NAVIGATE_URL = "url is required for navigate action. Example: {\"action\":\"navigate\",\"url\":\"https://example.com\"}";
    private static final String ERR_SELECTOR_REQUIRED = "selector is required for %s action";
    private static final String ERR_NO_HANDLER = "Chrome Extension bridge not connected";

    @Autowired
    private ApplicationEventPublisher eventPublisher;

    @Override
    public BuiltinToolEnum getToolType() {
        return BuiltinToolEnum.CHROME;
    }

    @Override
    public boolean needConfig() {
        return true;
    }

    @Override
    public ToolInfoVO getInfo() {
        ToolInfoVO info = new ToolInfoVO();
        info.setName(BuiltinToolConstants.NAME_PREFIX + CapabilityBuiltinToolChrome.class.getSimpleName());
        info.setDescription(DESCRIPTION);
        info.setDisplayNameCn("浏览器");
        info.setDisplayNameEn("Chrome");
        info.setParamSchema(ToolSchemaUtil.generateParamSchema(ChromeExecuteContext.class));
        info.setResponseSchema(ToolSchemaUtil.generateParamSchema(ChromeResultVO.class));
        return info;
    }

    @Override
    public Class<? extends ExecuteContext> getContextType() {
        return ChromeExecuteContext.class;
    }

    @Override
    public Class<? extends ExecuteResult> getResultType() {
        return ChromeResultVO.class;
    }

    @Override
    public ExecuteResult execute(ExecuteContext ctx) {
        ChromeExecuteContext cec = (ChromeExecuteContext) ctx;
        String commandId = UUID.randomUUID().toString();
        String action = cec.getAction();

        log.debug("Chrome command: {} (commandId={}, sessionId={})", action, commandId, cec.getSessionId());

        if (ACTION_NAVIGATE.equals(action) && (cec.getUrl() == null || cec.getUrl().isBlank())) {
            return ChromeResultVO.fail(ERR_NAVIGATE_URL);
        }
        if (ACTION_CLICK.equals(action) && (cec.getSelector() == null || cec.getSelector().isBlank()) && (cec.getText() == null || cec.getText().isBlank())) {
            return ChromeResultVO.fail("selector or text is required for click action");
        }
        if (ACTION_TYPE.equals(action) && (cec.getSelector() == null || cec.getSelector().isBlank())) {
            return ChromeResultVO.fail(String.format(ERR_SELECTOR_REQUIRED, ACTION_TYPE));
        }

        ChromeCommandDTO cmd = new ChromeCommandDTO(cec.getSessionId(), commandId, action)
                .withUrl(cec.getUrl())
                .withSelector(cec.getSelector())
                .withText(cec.getText())
                .withCode(cec.getCode())
                .withMode(cec.getMode())
                .withTabId(cec.getTabId())
                .withAppend(cec.getAppend())
                .withIndex(cec.getIndex())
                .withOccurrence(cec.getOccurrence())
                .withRef(cec.getRef())
                .withWaitMs(cec.getWaitMs())
                .withMs(cec.getMs())
                .withTimeout(cec.getTimeout())
                .withKey(cec.getKey())
                .withCodeKey(cec.getCodeKey())
                .withDirection(cec.getDirection())
                .withAmount(cec.getAmount())
                .withValue(cec.getValue())
                .withLabel(cec.getLabel())
                .withMax(cec.getMax())
                .withFileName(cec.getFileName())
                .withFileBase64(cec.getFileBase64())
                .withFileType(cec.getFileType())
                .withFrameId(cec.getFrameId())
                .withScope(cec.getScope())
                .withSubmit(cec.getSubmit())
                .withFields(cec.getFields())
                .withTextMax(cec.getTextMax());

        long timeoutSeconds = ACTION_NAVIGATE.equals(action) ? NAVIGATE_TIMEOUT_SECONDS : ACTION_TIMEOUT_SECONDS;
        if (cec.getTimeout() != null && cec.getTimeout() > 0) {
            timeoutSeconds = Math.max(2, cec.getTimeout());
        }

        try {
            eventPublisher.publishEvent(cmd);

            CompletableFuture<ChromeCallbackDTO> future = new CompletableFuture<>();
            ChromePendingStore.put(commandId, future);

            ChromeCallbackDTO cb = future.get(timeoutSeconds, TimeUnit.SECONDS);
            ChromePendingStore.remove(commandId);

            ChromeResultVO vo = cb.isSuccess()
                    ? ChromeResultVO.ok(cb.getData())
                    : ChromeResultVO.fail(cb.getError());
            vo.setErrorCategory(cb.getErrorCategory());
            vo.setMethod(cb.getMethod());
            vo.setResultType(cb.getResultType());
            vo.setWarning(cb.getWarning());
            return vo;

        } catch (InterruptedException e) {
            // run 被取消时 FiberSet 会 interrupt 本线程：立即返回，不再等待浏览器回调
            Thread.currentThread().interrupt();
            ChromePendingStore.remove(commandId);
            log.warn("Chrome command interrupted: {} (commandId={})", action, commandId);
            return ChromeResultVO.fail("Chrome operation cancelled");
        } catch (java.util.concurrent.TimeoutException e) {
            ChromePendingStore.remove(commandId);
            log.warn("Chrome command timed out: {} (commandId={})", action, commandId);
            return ChromeResultVO.fail(String.format("Chrome operation timed out after %ds", timeoutSeconds));
        } catch (Exception e) {
            ChromePendingStore.remove(commandId);
            log.warn("Chrome command failed: {}", action, e);
            return ChromeResultVO.fail(e.getMessage());
        }
    }
}
