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

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

@Slf4j
@Component
public class CapabilityBuiltinToolChrome implements CapabilityBuiltinToolSpi {

    private static final String DESCRIPTION = "Chrome browser automation. Operations in priority order:\n"
            + "1. navigate — open a URL in the browser.\n"
            + "2. getContent(mode: snapshot) — BEST for discovering the page: returns an indexable list of visible interactive elements, each with a stable `ref` (0-based), role/name/tag/type/state/href. Also returns `domHash` — a fingerprint of the page. Reuse `ref` directly in click/type/hover/select/upload to avoid selector guessing.\n"
            + "   getContent(mode: summary) — structured summary (inputs/buttons/forms/navLinks/sections/dialogs). Icon-only buttons are reported by aria-label/title/svg-title/alt.\n"
            + "   getContent(mode: query, selector) — compact list of up to 50 matches for repeated elements (each with index, class, frame).\n"
            + "   getContent(mode: extract, selector, fields) — structured extraction of repeated list blocks (e.g. candidate cards). `fields`: text (element text), .xxx (first sub-element text by CSS), @attr (attribute), href, value. Returns an array — ideal for reading N candidates/cards in ONE call instead of repeated full-DOM reads.\n"
            + "   getContent(mode: containers, selectors:[...] or selector:'a,b,c' as comma-separated) — cross-frame probe: for each selector returns {present, count, frame}. Run once after navigate to build a reusable container-scope map (e.g. .search-wrap/.dropdown-city/.degree-condition-ui/.double-select-container/.card-inner) for later scope-based actions.\n"
            + "   readInput(selector|ref|text, scope?) — read-only echo of the current input value (no scroll, no ambiguity gate).\n"
            + "3. click(ref) or click(selector) or click(text) — click an element. Auto-waits up to ~3s for the element to appear and be interactable. Text click is scoped to VISIBLE clickable elements (hidden/CSS text ignored). Duplicate text across sections (e.g. several '确定'/'北京') is resolved with `occurrence` (Nth) and `scope` (restrict search to a container selector like [role=dialog]). Result includes `changed` (true if the page likely changed), `_clickable` (false = no real click target, treat as mis-click), `_clickedTag`/`class`, and `_hints` (dialogs/chips/count). If `changed:false` and no URL change, do NOT re-read the page — try another approach or wait.\n"
            + "4. type(selector|ref, text, append?, submit?) — fill input fields (works with SPA frameworks). When append=true, append instead of replace. When submit=true, presses Enter after typing (e.g. to fire a search); result includes `_submitted`/`changed`. Result also includes `_echo` (the input's actual value right after typing) and `_echo_ok` (true if it fully equals `text`) — if `_echo_ok` is false, the field was not cleanly replaced (leftover/concatenated text): readInput-verify or clear+retype before submitting.\n"
            + "5. key(key) — send a keyboard key (Enter/Tab/Escape/Backspace/ArrowDown etc.) to the focused element (submit forms, close modals, shortcuts).\n"
            + "6. select(selector|ref, value|label) — choose an option in a <select>.\n"
            + "7. scroll(direction: up|down|left|right, amount?) or scroll(selector|ref) — scroll the page / to an element (infinite scroll, virtualized lists).\n"
            + "8. hover(selector/text) — reveal JS-driven hover menus.\n"
            + "9. upload(selector|ref, fileName, fileBase64, fileType?) — upload a file to an <input type=file> (works with drag-drop upload UIs).\n"
            + "10. wait(ms) or wait(selector|text|ref, timeout) — explicitly wait for dynamic content. Prefer `wait` over blind retries.\n"
            + "11. closeDialogs — close open modal dialogs/toasts.\n"
            + "12. executeJS — LAST RESORT (BLOCKED on strict-CSP sites). If csp_blocked/detached, do NOT retry — re-read with getContent snapshot and use click/type.\n"
            + "13. Tab following: clicking a link opening a new tab auto-switches control (result includes _newTabId/_newTabUrl). Pass `tabId` to target a specific tab.\n"
            + "14. Per-command timeout: pass `timeout` (seconds, min 2, max 30) to override the default (navigate 30s; wait 30s; others 10s) for slow re-renders.\n"
            + "15. frameId: pass `frameId=0` to address the TOP frame only (honored for getContent summary/query/extract/containers/snapshot and click/type/hover/readInput); leave null/empty to broadcast across same-origin frames. Many modal dialogs are rendered into the TOP-frame body (not inside an iframe) — use frameId=0 + `_hints.dialogs` to operate on them. Other frames are NOT injectable on most sites.\n"
            + "16. Repeated-failure guard: the same action+selector/text failing 2 times consecutively returns errorCategory='repeated_failure' — stop and switch strategy (snapshot ref / other selector / scope / drop the step).\n"
            + "17. 写动作(click/type/hover/key)统一走 CDP 受信输入（Input 事件，isTrusted=true）：定位由内容脚本跨同源 iframe 计算主视口坐标后派发。动作后请用结果里的 `_url` + getContent 复核实际状态（_url 变化/元素状态），不要假设一定生效。结果 `_executed=true` 表示已派发。同一定位有多个可见命中且未给 index/occurrence/scope 时返回 `ambiguous`（结果带 `suggested` 建议容器 scope，请补 index/scope 后重试）。iframe 内元素禁止用顶层 snapshot ref，优先 getContent 广播 + 容器 scope。返回 wrong_site 表示受控 tab 已离开目标站点，应先 navigate 回主站并重建容器 map。**csp_blocked 只代表某个 iframe 不能执行 JS：顶层 DOM 仍可操作，切 frameId=0 继续，勿判定整个功能不可用。**"
            + "Rules: prefer getContent(snapshot)+ref for everything. Use `_hints.dialogs` to know a modal opened (framework dialogs such as those transferred into the top-frame body are also reported) and close it via closeDialogs or its 确定/取消 button (scope it). `_hints.count` shows result-count text (e.g. '共有 N 份简历') — filter counts may update asynchronously; wait() 1-2s before concluding a filter had no effect. If an action returns not_found/not_interactable, use wait() for a dynamic element or re-read the snapshot (refs may shift after DOM changes). Never blindly retry after csp_blocked/detached; when a frame is blocked, re-target with frameId=0. If click returns changed:false, the page did not react — pick another approach.\n"
            + "When a strict filter combination returns 0 results, loosen filters stepwise (remove the most specific first) and evaluate candidates per-item against ALL criteria — do not settle for a zero-result dead end.\n"
            + "Results include `errorCategory` (not_found / csp_blocked / detached / inject_failed / timeout / no_tab / unknown) and `method`.";
    private static final long NAVIGATE_TIMEOUT_SECONDS = 30;
    private static final long WAIT_TIMEOUT_SECONDS = 30;
    private static final long ACTION_TIMEOUT_SECONDS = 10;
    private static final long MAX_COMMAND_TIMEOUT_SECONDS = 30;

    private static final String ACTION_NAVIGATE = "navigate";
    private static final String ACTION_WAIT = "wait";
    private static final String ACTION_CLICK = "click";
    private static final String ACTION_TYPE = "type";

    private static final String ERR_NAVIGATE_URL = "url is required for navigate action. Example: {\"action\":\"navigate\",\"url\":\"https://example.com\"}";
    private static final String ERR_SELECTOR_REQUIRED = "selector is required for %s action";
    private static final String ERR_NO_HANDLER = "Chrome Extension bridge not connected";

    // ---- 连续失败护栏：同一 session+action+定位 连续失败达到阈值即停手，防烧任务轮次 ----
    private static final int CONSECUTIVE_FAIL_LIMIT = 2;
    private static final long FAIL_TRACK_TTL_MILLIS = 10 * 60 * 1000L;
    private static final String ERROR_CATEGORY_REPEATED = "repeated_failure";
    private static final String INJECT_FAILED = "inject_failed";
    private static final String ERROR_CATEGORY_INVALID_REQUEST = "invalid_request";
    private static final String ERROR_CATEGORY_TIMEOUT = "timeout";
    private static final String ERROR_CATEGORY_CANCELLED = "cancelled";
    private static final String ERROR_CATEGORY_UNKNOWN = "unknown";

    @Autowired
    private ApplicationEventPublisher eventPublisher;

    private final Map<String, FailEntry> consecutiveFailures = new ConcurrentHashMap<>();

    private static final class FailEntry {
        int count;
        long lastTs = System.currentTimeMillis();
    }

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
        String action = cec.getAction();

        log.debug("Chrome command: {} (sessionId={})", action, cec.getSessionId());

        // 帧语义：frameId 仅支持 0（主框架）或留空（全部广播）；其他 frame 不可用（AGENT 实测 1/2/3 报错）
        if (cec.getFrameId() != null && cec.getFrameId() > 0) {
            return failed("frameId 仅支持 0（主框架）或留空；其他 frame 不可用",
                    ERROR_CATEGORY_INVALID_REQUEST, cec.getFrameId());
        }

        if (ACTION_NAVIGATE.equals(action) && (cec.getUrl() == null || cec.getUrl().isBlank())) {
            return failed(ERR_NAVIGATE_URL, ERROR_CATEGORY_INVALID_REQUEST, cec.getFrameId());
        }
        if (ACTION_CLICK.equals(action) && (cec.getSelector() == null || cec.getSelector().isBlank()) && (cec.getText() == null || cec.getText().isBlank())) {
            return failed("selector or text is required for click action",
                    ERROR_CATEGORY_INVALID_REQUEST, cec.getFrameId());
        }
        if (ACTION_TYPE.equals(action) && (cec.getSelector() == null || cec.getSelector().isBlank())) {
            return failed(String.format(ERR_SELECTOR_REQUIRED, ACTION_TYPE),
                    ERROR_CATEGORY_INVALID_REQUEST, cec.getFrameId());
        }

        // 连续失败护栏：命中即停手，避免 LLM 反复重试同一坏定位烧光任务轮次
        String failKey = failKey(cec);
        FailEntry hit = consecutiveFailures.get(failKey);
        if (hit != null && hit.count >= CONSECUTIVE_FAIL_LIMIT) {
            log.warn("Chrome repeated-failure guard: {} consecutive failures for key={}",
                    hit.count, failKey);
            ChromeResultVO vo = failed("连续 " + CONSECUTIVE_FAIL_LIMIT
                    + " 次失败(" + action + ")。请改用 snapshot ref 重定位、更换 selector，或另选方案");
            vo.setErrorCategory(ERROR_CATEGORY_REPEATED);
            vo.setAttemptedFrameId(cec.getFrameId());
            return vo;
        }

        long timeoutSeconds = ACTION_NAVIGATE.equals(action) ? NAVIGATE_TIMEOUT_SECONDS
                : ACTION_WAIT.equals(action) ? WAIT_TIMEOUT_SECONDS
                : ACTION_TIMEOUT_SECONDS;
        if (cec.getTimeout() != null && cec.getTimeout() > 0) {
            timeoutSeconds = Math.max(2, Math.min(MAX_COMMAND_TIMEOUT_SECONDS, cec.getTimeout()));
        }

        ChromeCallbackDTO cb = null;
        for (int attempt = 0; attempt < 2; attempt++) {
            if (attempt > 0) {
                try {
                    Thread.sleep(300);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return cancelled(cec.getFrameId());
                }
            }
            String commandId = UUID.randomUUID().toString();
            ChromeCommandDTO cmd = buildCommand(cec, commandId);
            CompletableFuture<ChromeCallbackDTO> future = new CompletableFuture<>();
            // 先注册 pending，再发布事件，避免扩展 callback 在发布事件期间抢先到达而丢失。
            ChromePendingStore.put(commandId, future);
            try {
                eventPublisher.publishEvent(cmd);
                ChromeCallbackDTO r = future.get(timeoutSeconds, TimeUnit.SECONDS);
                if (r == null) {
                    cb = ChromeCallbackDTO.fail(commandId, "Chrome callback was empty");
                    cb.setErrorCategory(ERROR_CATEGORY_UNKNOWN);
                    break;
                }
                // inject_failed（导航/重注入竞态）→ 单次自动重发；其余失败直接返回
                if (r.isSuccess() || !INJECT_FAILED.equals(r.getErrorCategory()) || attempt == 1) {
                    cb = r;
                    break;
                }
                log.warn("Chrome command inject_failed, auto-retrying once: {} (commandId={})", action, commandId);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("Chrome command interrupted: {} (commandId={})", action, commandId);
                return cancelled(cec.getFrameId());
            } catch (java.util.concurrent.TimeoutException e) {
                log.warn("Chrome command timed out: {} (commandId={})", action, commandId);
                return timedOut(timeoutSeconds, cec.getFrameId());
            } catch (Exception e) {
                log.warn("Chrome command failed: {}", action, e);
                return failed(e.getMessage(), ERROR_CATEGORY_UNKNOWN, cec.getFrameId());
            } finally {
                // complete() 已经 remove 时这里是 no-op；异常、超时和迟到 callback 也不会泄漏。
                ChromePendingStore.remove(commandId);
            }
        }
        if (cb == null) {
            return failed(ERR_NO_HANDLER, ERROR_CATEGORY_UNKNOWN, cec.getFrameId());
        }

        ChromeResultVO vo = cb.isSuccess()
                ? ChromeResultVO.ok(cb.getData())
                : ChromeResultVO.fail(cb.getError());
        vo.setErrorCategory(cb.getErrorCategory() == null
                ? ERROR_CATEGORY_UNKNOWN : cb.getErrorCategory());
        vo.setMethod(cb.getMethod());
        vo.setResultType(cb.getResultType());
        vo.setWarning(cb.getWarning());
        vo.setAttemptedFrameId(cec.getFrameId());

        if (cb.isSuccess()) {
            consecutiveFailures.remove(failKey);
        } else {
            recordFailure(failKey);
        }
        return vo;
    }

    private ChromeCommandDTO buildCommand(ChromeExecuteContext cec, String commandId) {
        return new ChromeCommandDTO(cec.getSessionId(), commandId, cec.getAction())
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
                .withSelectors(cec.getSelectors())
                .withTextMax(cec.getTextMax());
    }

    private String failKey(ChromeExecuteContext cec) {
        StringBuilder sb = new StringBuilder()
                .append(cec.getSessionId()).append('|')
                .append(cec.getRunId()).append('|')
                .append(cec.getTabId()).append('|')
                .append(cec.getAction()).append('|');
        if (cec.getSelector() != null && !cec.getSelector().isBlank()) sb.append(cec.getSelector());
        else if (cec.getText() != null && !cec.getText().isBlank()) sb.append(cec.getText());
        else if (cec.getRef() != null) sb.append("ref:").append(cec.getRef());
        return sb.toString();
    }

    private void recordFailure(String key) {
        FailEntry e = consecutiveFailures.computeIfAbsent(key, k -> new FailEntry());
        e.count++;
        e.lastTs = System.currentTimeMillis();
        if (consecutiveFailures.size() > 512) {
            long cutoff = System.currentTimeMillis() - FAIL_TRACK_TTL_MILLIS;
            consecutiveFailures.entrySet().removeIf(en -> en.getValue().lastTs < cutoff);
        }
    }

    private ChromeResultVO cancelled(Integer frameId) {
        ChromeResultVO vo = ChromeResultVO.fail("Chrome operation cancelled");
        vo.setErrorCategory(ERROR_CATEGORY_CANCELLED);
        vo.setAttemptedFrameId(frameId);
        return vo;
    }

    private ChromeResultVO timedOut(long seconds, Integer frameId) {
        ChromeResultVO vo = ChromeResultVO.fail(String.format("Chrome operation timed out after %ds", seconds));
        vo.setErrorCategory(ERROR_CATEGORY_TIMEOUT);
        vo.setAttemptedFrameId(frameId);
        // 慢页面超时通常不等于失败：操作可能已生效。
        // 提示 LLM 先 getContent 确认实际状态，不要盲目重试同一 selector。
        vo.setWarning("操作可能已生效（slow page），请先 getContent 确认实际状态，勿盲目重试同一 selector");
        return vo;
    }

    private ChromeResultVO failed(String message) {
        return failed(message, ERROR_CATEGORY_UNKNOWN, null);
    }

    private ChromeResultVO failed(String message, String category, Integer frameId) {
        ChromeResultVO vo = ChromeResultVO.fail(message);
        vo.setErrorCategory(category);
        vo.setAttemptedFrameId(frameId);
        return vo;
    }
}
