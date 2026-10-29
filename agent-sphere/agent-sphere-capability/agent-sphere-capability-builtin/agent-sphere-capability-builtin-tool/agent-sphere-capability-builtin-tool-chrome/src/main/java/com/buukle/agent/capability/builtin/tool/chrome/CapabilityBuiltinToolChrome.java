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

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

@Slf4j
@Component
public class CapabilityBuiltinToolChrome implements CapabilityBuiltinToolSpi {

    private static final String DESCRIPTION = """
            Chrome browser automation (one command at a time). Actions:
            0. After navigate, call getContent(mode=snapshot) FIRST and read `frames` + each item's `frame`; operate on the owning same-origin frame. Never blind-click a shell page.
            1. navigate(url)
            2. getContent: snapshot (visible interactive elements with stable `ref`, role/name/frame/domHash; best default), summary (inputs/buttons/forms/nav/sections/dialogs), axtree (browser accessibility tree; OBSERVATION ONLY), query(selector) (confirm active chips/dropdowns; >100 matches => tooBroad), extract(selector, fields) (read repeated cards/list blocks in ONE call), containers(selectors) (cross-frame presence map), ready(selectors, waitMs) (one-shot readiness + frames/containers). readInput(selector|ref|text, scope?) echoes current value.
            3. click(ref|selector|text) (auto-waits; use occurrence/scope for duplicates). Read `changed`, `_selfChanged` (target itself changed), `_clickable`, `_hints`, `_url`. If changed=false and no URL change the page did NOT react — do not re-read blindly; try another approach or wait.
            4. type(selector|ref, text, append?, submit?) (SPA-safe; `_echo`/`_echo_ok`; clear+retype if echo mismatch).
            5. key(key) — Enter/Tab/Escape/Backspace/ArrowDown etc.
            6. select(selector|ref, value|label)
            7. scroll(direction, amount?) or scroll(selector|ref)
            8. hover(selector/text) — reveal JS flyouts.
            9. upload(selector|ref, fileName, fileBase64, fileType?)
            10. Waiting: use the global `wait` tool or getContent(mode=ready, selectors, waitMs). Chrome has NO wait action.
            11. closeDialogs
            12. executeJS — LAST RESORT (strict CSP may block). READ-ONLY ONLY; must return small JSON. Navigation writes are rejected (js_navigation_blocked). Do not retry csp_blocked/detached — re-read snapshot and use click/type.
              NEVER use executeJS for long-running loops, polling, sleeps, delays, or waiting.
              - Need to wait: call the global `wait` tool.
              - Need to process many items: split into small steps; each executeJS is one short single-purpose read.
              - Do not use setTimeout / setInterval / long await loops inside executeJS.
              - Keep each executeJS under ~7 seconds.
            13. Tabs: links opening a new tab auto-switch (`_newTabId`/`_newTabUrl`); pass `tabId` to target one.
            14. Per-command timeout: seconds 2-30 (navigate 30s, executeJS 30s, others 15s).
            15. frameId=0 addresses the TOP frame only; null broadcasts across same-origin frames.
            16. Expanding panels: hover/click trigger -> getContent(mode=ready, selectors:['[role=listbox] li'], waitMs) -> query to confirm -> click the scoped item. Never click an empty/absent li.
            17. Filters: set-then-verify each one; check active chips/container text and `_hints.count`; do not batch-set filters.
            18. Concurrency guard: one command at a time. On "上一命令仍在执行" wait ~retryAfterMs or verify state; never burst.
            19. Repeated-failure guard: the same action+target failing twice returns repeated_failure — switch strategy.
            20. screenshot(scope=viewport|full, format, quality): viewport is the only clickAt target; a user image observation follows.
            21. clickAt(x, y): device-pixel coordinates of the LAST viewport screenshot; check `changed`/`_clickable`.
            Rules: prefer snapshot+ref; use `_hints.dialogs`/`chips`; not_found/not_interactable -> wait/ready or re-snapshot (refs may shift); never blindly retry csp/detached; click/type/hover/key use trusted CDP input; ambiguous/occluded/not_interactable/wrong_site have specific handling; check `_selfChanged` before declaring failure. When a filter returns 0, report the filter conditions; never lower the task's acceptance criteria. Results include errorCategory/method/warning.
            """;
    private static final long NAVIGATE_TIMEOUT_SECONDS = 30;
    // 交互动作（click/type/hover/key/select/upload/…）15s：动作本身是毫秒级的，但扩展侧的
    // 写动作现在会先画光标（到达屏障 1.5s）+ CDP 拟人轨迹，且背景 service worker 偶有冷启动，
    // 10s 偏紧。15s 仍远低于扩展侧 COMMAND_LEASE_MS=60s，也留足了「后端先放弃会撞并发守卫」
    // 的余量：最坏 2×15s+0.3s = 30.3s，仍 < 全局 tool.execution-timeout 60s。
    private static final long ACTION_TIMEOUT_SECONDS = 15;
    // executeJS 是唯一需要长耗时的动作（页面里等条件、等异步接口都可能十几秒），
    // 单独给 30s。扩展侧 EXECJS_TIMEOUT_MS=27s 会先返回干净的 timeout，
    // 比后端先放弃更好 —— 否则扩展仍持租约，迟到回调被丢弃，下一命令还会撞上并发守卫。
    private static final long EXECUTEJS_TIMEOUT_SECONDS = 30;
    private static final long MAX_COMMAND_TIMEOUT_SECONDS = 30;

    private static final String ACTION_NAVIGATE = "navigate";
    private static final String ACTION_EXECUTE_JS = "executeJS";
    private static final String ACTION_CLICK = "click";
    private static final String ACTION_TYPE = "type";
    private static final String ACTION_SCREENSHOT = "screenshot";
    private static final String ACTION_CLICK_AT = "clickAt";

    private static final String ERR_NAVIGATE_URL = "url is required for navigate action. Example: {\"action\":\"navigate\",\"url\":\"https://example.com\"}";
    private static final String ERR_SELECTOR_REQUIRED = "selector is required for %s action";

    // ---- 连续失败护栏：同一 session+action+定位 连续失败达到阈值即停手，防烧任务轮次 ----
    private static final int CONSECUTIVE_FAIL_LIMIT = 2;
    private static final long FAIL_TRACK_TTL_MILLIS = 10 * 60 * 1000L;
    private static final String ERROR_CATEGORY_REPEATED = "repeated_failure";
    private static final String ERROR_CATEGORY_INVALID_REQUEST = "invalid_request";
    private static final String ERROR_CATEGORY_TIMEOUT = "timeout";
    private static final String ERROR_CATEGORY_CANCELLED = "cancelled";
    private static final String ERROR_CATEGORY_UNKNOWN = "unknown";
    /** executeJS 里做导航写操作 —— 见 JS_NAVIGATION_WRITE_PATTERNS。 */
    private static final String ERROR_CATEGORY_JS_NAVIGATION_BLOCKED = "js_navigation_blocked";

    /**
     * executeJS 的**执行范围**：只允许只读（读 DOM/取值/计算并 return 小 JSON）。
     *
     * <p>以下模式属于**导航写操作**，一律拒绝执行。原因：脚本式跳转（尤其
     * {@code location.href = ...} 与 {@code history.back()}）会绕过真实点击链路与
     * referer/点击语境，是最强的自动化指纹 —— 线上任务正是因此被判异常并跳转到
     * 站点的风控验证页。详情/返回必须走 click 与平台控件。
     *
     * <p>这是**防协作型 agent 走错路**，不是防对抗：静态字符串匹配足够，不追求不可绕过。
     * <b>本清单是唯一权威出处</b>（插件端不再复制一份）。
     */
    private static final List<Pattern> JS_NAVIGATION_WRITE_PATTERNS = List.of(
            // location.xxx = value  （href/hash/search/pathname/host/... 任一属性赋值都会触发导航）
            Pattern.compile("location\\s*\\.\\s*[A-Za-z_$][\\w$]*\\s*=(?!=)"),
            // location['xxx'] = value
            Pattern.compile("location\\s*\\[[^\\]]+\\]\\s*=(?!=)"),
            // location.assign( / replace( / reload(
            Pattern.compile("location\\s*\\.\\s*(assign|replace|reload)\\s*\\("),
            // document.location / window.location / top.location / self.location / parent.location 上的赋值
            Pattern.compile("(document|window|top|self|parent)\\s*\\.\\s*location\\s*"
                    + "(\\.\\s*[A-Za-z_$][\\w$]*\\s*=|\\[[^\\]]+\\]\\s*=|=(?!=))"),
            // history 导航
            Pattern.compile("history\\s*\\.\\s*(pushState|replaceState|go|back|forward)\\s*\\("),
            // 新窗口
            Pattern.compile("window\\s*\\.\\s*open\\s*\\("),
            // 表单提交（会整页导航）
            Pattern.compile("\\.\\s*submit\\s*\\(")
    );

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

        // executeJS 只读闸：命中导航写操作 → 直接失败且**不构造命令**
        // （浏览器命令的唯一出口是 buildCommand，故此处拦截即全网生效）。
        if (ACTION_EXECUTE_JS.equals(action)) {
            String hit = matchNavigationWrite(cec.getCode());
            if (hit != null) {
                log.warn("Blocked executeJS navigation write: sessionId={} pattern={}",
                        cec.getSessionId(), hit);
                return failed("executeJS 仅允许只读操作，检测到导航写操作（命中模式 " + hit
                                + "）。打开/返回详情请用 click + 平台控件，整页跳转请用 navigate",
                        ERROR_CATEGORY_JS_NAVIGATION_BLOCKED, cec.getFrameId());
            }
        }

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
                : ACTION_EXECUTE_JS.equals(action) ? EXECUTEJS_TIMEOUT_SECONDS
                : ACTION_TIMEOUT_SECONDS;
        if (cec.getTimeout() != null && cec.getTimeout() > 0) {
            timeoutSeconds = Math.max(2, Math.min(MAX_COMMAND_TIMEOUT_SECONDS, cec.getTimeout()));
        }
        try {
            Thread.sleep(600);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return cancelled(cec.getFrameId());
        }

        // 单次执行：不再对 inject_failed 自动重发。导航/重注入竞态如实回报给模型，
        // 由模型按提示先 wait/getContent 再操作（扩展内部的通道自愈仍保留，见 tab-manager）。
        ChromeCallbackDTO cb;
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
            } else {
                cb = r;
            }
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

    /**
     * 检测 executeJS 代码里是否含导航写操作。
     *
     * @return 命中的模式字符串（便于告知 agent 具体改哪一处）；未命中返回 {@code null}
     */
    static String matchNavigationWrite(String code) {
        if (code == null || code.isBlank()) {
            return null;
        }
        for (Pattern p : JS_NAVIGATION_WRITE_PATTERNS) {
            if (p.matcher(code).find()) {
                return p.pattern();
            }
        }
        return null;
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
