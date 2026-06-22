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
            + "2. getContent(mode: summary) — discover interactive elements, navigation links (navLinks), expandable sections, and open dialogs/modals (dialogs). Returns inputs/buttons/forms/navLinks/sections/dialogs. Icon-only buttons are reported by their aria-label or title attribute (e.g. 'plus').\n"
            + "3. click(selector) or click(text) — click an element by CSS selector OR by visible text. Text-based click uses 3-phase fuzzy matching: exact text → contains(text) → contains(any descendant) with up-search to nearest clickable ancestor. Works even when text is nested in child elements (e.g. menus, submenu-titles).\n"
            + "4. type(selector, text, append?): fill input fields (works with modern SPA frameworks like React/Vue/Angular). "
            + "When append=true, text is appended to existing content instead of replacing it. Useful for incrementally writing articles, multi-line inputs, or chat message boxes.\n"
            + "   For Draft.js editors (Zhihu article editor, Facebook): first click() the editor area (e.g. by placeholder text '请输入正文') to activate it, "
            + "then type() on selector '.public-DraftEditor-content'. If type() returns success but no visible text change, the editor likely needs activation first.\n"
            + "5. executeJS — last resort for complex interactions when other actions fail. If the JS expression has no return value (undefined), the result data is '__NO_RETURN__' with _resultType 'void'.\n"
            + "6. Tab following: clicking a link that opens a new tab (target=\"_blank\" or window.open) will automatically switch control to the new tab. The result will include _newTabId and _newTabUrl.\n"
            + "The _url field in click/getContent results reflects the page URL after SPA route changes (500ms polling window).\n"
            + "Always prefer getContent to discover the page structure, and text-based click over CSS selectors for navigation elements.\n"
            + "7. Multi-tab management: navigate returns tabId. To operate a specific tab, pass the tabId parameter to getContent/click/type/executeJS. Without tabId, operations target the last navigated tab.";
    private static final long TIMEOUT_SECONDS = 30;

    private static final String ACTION_NAVIGATE = "navigate";
    private static final String ACTION_CLICK = "click";
    private static final String ACTION_TYPE = "type";

    private static final String ERR_NAVIGATE_URL = "url is required for navigate action. Example: {\"action\":\"navigate\",\"url\":\"https://example.com\"}";
    private static final String ERR_SELECTOR_REQUIRED = "selector is required for %s action";
    private static final String ERR_NO_HANDLER = "Chrome Extension bridge not connected";
    private static final String ERR_TIMEOUT = "Chrome operation timed out after %ds";

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
                .withAppend(cec.getAppend());

        try {
            eventPublisher.publishEvent(cmd);

            CompletableFuture<ChromeCallbackDTO> future = new CompletableFuture<>();
            ChromePendingStore.put(commandId, future);

            ChromeCallbackDTO cb = future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            ChromePendingStore.remove(commandId);

            return cb.isSuccess()
                    ? ChromeResultVO.ok(cb.getData())
                    : ChromeResultVO.fail(cb.getError());

        } catch (java.util.concurrent.TimeoutException e) {
            ChromePendingStore.remove(commandId);
            log.warn("Chrome command timed out: {} (commandId={})", action, commandId);
            return ChromeResultVO.fail(String.format(ERR_TIMEOUT, TIMEOUT_SECONDS));
        } catch (Exception e) {
            ChromePendingStore.remove(commandId);
            log.warn("Chrome command failed: {}", action, e);
            return ChromeResultVO.fail(e.getMessage());
        }
    }
}
