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
            + "0. FIRST STEP AFTER navigate — build the frame map. The top page may be a SHELL with the real controls inside a same-origin iframe (e.g. a search page whose controls render into /web/frame/search/). Run getContent(mode: snapshot) and read top-level `frames` (list of {frame:iframeUrl, interactables, sameOrigin}) plus each item's `frame` BEFORE touching selectors, so you know where each control lives. If `frames` shows a content frame with interactables, operate on those controls cross-frame (items already carry `frame`); never keep blind-clicking a top-level-only snapshot.\n"
            + "1. navigate — open a URL in the browser.\n"
            + "2. getContent(mode: snapshot) — BEST for discovering the page: indexable list of visible interactive elements across same-origin frames, each with stable `ref` (0-based), role/name/tag/type/state/href, and `frame` (owning frame URL). Also `domHash` (cross-frame fingerprint) + `frames` (frame map). Reuse `ref` in click/type/hover/select/upload to avoid selector guessing.\n"
            + "   getContent(mode: summary) — structured summary (inputs/buttons/forms/navLinks/sections/dialogs). Icon-only buttons reported by aria-label/title/svg-title/alt.\n"
            + "   getContent(mode: query, selector) — compact list of up to 50 matches (index/class/frame). Use it to CONFIRM state: active filter chips (e.g. .degree-item.active), expanded dropdown items, etc.\n"
            + "   getContent(mode: extract, selector, fields) — structured extraction of repeated list blocks (candidate cards). `fields`: text, .xxx (first sub-element text), @attr, href, value. Returns an array — read N candidates/cards in ONE call.\n"
            + "   getContent(mode: containers, selectors:[...] or selector:'a,b,c') — cross-frame probe: {present,count,frame} per selector. Run once after navigate to build a reusable container-scope map.\n"
            + "   getContent(mode: ready, selectors:[...], waitMs?) — ONE-SHOT readiness+mapping after navigate: waits waitMs (default 1.2s) then returns {frames, containers, ready (all selectors present), missing, _hints}. If ready:false, wait and re-run ready — do NOT spam empty probes across rounds.\n"
            + "   query returns `tooBroad:true` when a selector matches >100 nodes — narrow with containers+scope instead of wide scans.\n"
            + "   readInput(selector|ref|text, scope?) — read-only echo of the current input value.\n"
            + "3. click(ref|selector|text) — click an element (auto-waits ~3s). Duplicate text resolved with `occurrence` + `scope`. Result carries REAL post-action state: `changed` (cross-frame fingerprint differed before/after), `_clickable` (false = no real click target, treat as mis-click), `_hints` (dialogs/chips/count) and `_url`. If `changed:false` and no URL change, the page did NOT react — do NOT re-read the page, try another approach or wait.\n"
            + "4. type(selector|ref, text, append?, submit?) — fill inputs (SPA-safe). append=true appends; submit=true presses Enter (e.g. trigger search). Result includes `_echo` (real field value after typing) and `_echo_ok` (fully equals text — if false, clear+retype before submitting), plus `changed`/`_hints`.\n"
            + "5. key(key) — send Enter/Tab/Escape/Backspace/ArrowDown etc. to the focused element (submit forms, close modals, shortcuts).\n"
            + "6. select(selector|ref, value|label) — choose an option in a <select>.\n"
            + "7. scroll(direction, amount?) or scroll(selector|ref) — page / to-element scroll (infinite, virtualized lists).\n"
            + "8. hover(selector/text) — reveal JS-driven flyout menus (two-level interaction, see rule 16).\n"
            + "9. upload(selector|ref, fileName, fileBase64, fileType?) — upload to an <input type=file> (drag-drop UIs).\n"
            + "10. wait(ms) or wait(selector|text|ref, timeout) — explicitly wait for dynamic content. Prefer explicit wait over blind retries.\n"
            + "11. closeDialogs — close open modal dialogs/toasts.\n"
            + "12. executeJS — LAST RESORT (BLOCKED on strict-CSP sites). Code runs as an async block: you MUST `return` a JSON-serializable value. If it returns nothing you get data:'__NO_RETURN__' + `_documentTitle` — that means the script EXECUTED FINE but returned no value (NOT a failure), do NOT retry. To verify a side-effect, set `document.title='<token>|<state>'` inside the script and read `_documentTitle` back. If csp_blocked/detached, do NOT retry — re-read with getContent snapshot and use click/type.\n"
            + "13. Tab following: a link opening a new tab auto-switches control (result includes _newTabId/_newTabUrl). Pass `tabId` to target a specific tab.\n"
            + "14. Per-command timeout: pass `timeout` (seconds, min 2, max 30) to override defaults (navigate 30s; wait 30s; others 10s) for slow re-renders.\n"
            + "15. frameId: pass `frameId=0` to address the TOP frame only; leave null to broadcast across same-origin frames. Many modal dialogs render into the TOP-frame body — use frameId=0 + `_hints.dialogs`. Frames other than 0 are NOT addressable.\n"
            + "16. Two-level (expanding) panels — city/job/industry dropdowns, hover menus: while collapsed the target li/option is NOT in the DOM, so direct clicks on li fail. ALWAYS: (a) hover or click the trigger → (b) wait(600~1500) for the flyout to render → (c) getContent(query, '.ui-dropmenu-list li, .square-wrap li, [class*=dropdown] li, [role=option], [role=listbox] li') to CONFIRM the list appeared → (d) then click the target item (scope it to the list container). Never click an empty li, never click the tag body without confirming expansion.\n"
            + "17. Filter conditions (city/salary/education/experience on job sites) — SET-THEN-VERIFY per filter. After setting each one, confirm via getContent(query, '.salary-container, [class*=salary], .degree-item.active, .exp-item.active, [aria-selected=true]') or read `_hints.chips`; salary pickers often allow 'XK-不限' — confirm the container text equals exactly what you set. `_hints.count` ('共有 N 份简历') updates asynchronously — wait 1-2s before concluding a filter had no effect. Do NOT batch-set several filters before checking; leftover panel state pollutes later steps.\n"
            + "18. Concurrency guard: the extension runs ONE command at a time. '上一命令仍在执行（并发守卫）' responses carry `_runningAction`/`elapsedMs`/`retryAfterMs` — wait ~retryAfterMs (or verify current state via getContent) before the NEXT command; never burst the same command.\n"
            + "19. Repeated-failure guard: the same action+selector/text failing 2 times consecutively returns errorCategory='repeated_failure' — stop and switch strategy (snapshot ref / other selector / scope / drop the step).\n"
            + "20. screenshot(scope=viewport|full, format=jpeg|png, quality 0-100) — capture the controlled tab. Only viewport screenshots can be used as clickAt targets (no scroll offset for full-page). After a screenshot succeeds, a USER observation message with the image is injected into the conversation on your next turn — use it to reason about layout/state. full-page screenshots are for observation only, NOT clickAt targets.\n"
            + "21. clickAt(x, y) — trusted click at device-pixel coordinates of the LAST viewport screenshot (the execution layer converts to CSS px using dpr, so pass raw screenshot pixels). If the point lands on a non-interactive area the click still executes and a warning is returned — always check `changed`/`_clickable` before concluding, and never assume a click on a container div succeeded. Prefer snapshot refs when precision matters; use clickAt for pixel-targeted vision operation.\n"
            + "Rules: prefer getContent(snapshot)+ref for everything. Use `_hints.dialogs` to know a modal opened (framework dialogs transferred into the top-frame body are reported too) and close it via closeDialogs or its 确定/取消 button (scoped). `_hints.chips` = active filter-chip fingerprint — read it to confirm filter toggles. If an action returns not_found/not_interactable, wait() for dynamic content or re-read the snapshot (refs may shift after DOM changes). Never blindly retry after csp_blocked/detached; a blocked frame only means THAT frame's JS is blocked — top DOM still operates, switch frameId=0 and continue. 写动作(click/type/hover/key)统一走 CDP 受信输入（isTrusted=true）：定位由内容脚本跨同源 iframe 计算主视口坐标后派发；动作结果带真实 changed/_hints/_clickable，先看结果再决定下一步，不要假设已生效。same 定位多个可见命中且未给 index/occurrence/scope → ambiguous（带 suggested scope），补 index/scope 重试。wrong_site 表示受控 tab 已离开目标站点，先 navigate 回主站并重建 frame/容器 map。\n"
            + "When a strict filter combination returns 0 results, loosen filters stepwise (remove the most specific first) and evaluate candidates per-item against ALL criteria — do not settle for a zero-result dead end.\n"
            + "Results include `errorCategory` (not_found / csp_blocked / detached / inject_failed / timeout / no_tab / wrong_site / ambiguous / unknown), `method`, `warning`.";
    private static final long NAVIGATE_TIMEOUT_SECONDS = 30;
    private static final long WAIT_TIMEOUT_SECONDS = 30;
    private static final long ACTION_TIMEOUT_SECONDS = 10;
    private static final long MAX_COMMAND_TIMEOUT_SECONDS = 30;

    private static final String ACTION_NAVIGATE = "navigate";
    private static final String ACTION_WAIT = "wait";
    private static final String ACTION_CLICK = "click";
    private static final String ACTION_TYPE = "type";
    private static final String ACTION_SCREENSHOT = "screenshot";
    private static final String ACTION_CLICK_AT = "clickAt";

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
        if (ACTION_CLICK_AT.equals(action)
                && (cec.getX() == null || cec.getY() == null)) {
            return failed("clickAt 需要 x/y（最后一张 viewport 截图的设备像素坐标）",
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
                .withTextMax(cec.getTextMax())
                .withX(cec.getX())
                .withY(cec.getY())
                .withFormat(cec.getFormat())
                .withQuality(cec.getQuality());
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
        else if (ACTION_CLICK_AT.equals(cec.getAction())) sb.append("x:").append(cec.getX()).append(",y:").append(cec.getY());
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
