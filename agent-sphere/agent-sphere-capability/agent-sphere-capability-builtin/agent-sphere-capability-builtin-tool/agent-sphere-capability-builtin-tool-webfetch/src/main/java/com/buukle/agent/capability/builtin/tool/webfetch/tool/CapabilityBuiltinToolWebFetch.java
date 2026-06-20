package com.buukle.agent.capability.builtin.tool.webfetch.tool;

import com.buukle.agent.capability.builtin.dtvo.enums.BuiltinToolEnum;
import com.buukle.agent.capability.builtin.tool.spi.CapabilityBuiltinToolSpi;
import com.buukle.agent.capability.builtin.tool.spi.constant.BuiltinToolConstants;
import com.buukle.agent.capability.builtin.tool.spi.dtvo.ExecuteContext;
import com.buukle.agent.capability.builtin.tool.spi.dtvo.ExecuteResult;
import com.buukle.agent.capability.builtin.tool.spi.dtvo.ToolInfoVO;
import com.buukle.agent.capability.builtin.tool.spi.util.ToolSchemaUtil;
import com.buukle.agent.capability.builtin.tool.webfetch.dtvo.dto.WebFetchExecuteContext;
import com.buukle.agent.capability.builtin.tool.webfetch.dtvo.vo.WebFetchResultVO;
import com.buukle.agent.capability.builtin.tool.webfetch.util.BrowserProfile;
import com.buukle.agent.capability.builtin.tool.webfetch.util.BrowserProfileManager;
import com.buukle.agent.capability.builtin.tool.webfetch.util.FetchRetryPolicy;
import com.buukle.agent.capability.builtin.tool.webfetch.util.JsRenderer;
import com.buukle.agent.common.config.AgentRuntimeProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

@Slf4j
@Component
public class CapabilityBuiltinToolWebFetch implements CapabilityBuiltinToolSpi {

    private static final String DESCRIPTION = "Fetch web page content by URL";
    private static final String HEADER_CONTENT_TYPE = "Content-Type";

    private final HttpClient httpClient;
    private final int defaultReadTimeoutSeconds;
    private final FetchRetryPolicy retryPolicy;
    private final BrowserProfileManager profileManager;
    private final JsRenderer jsRenderer;
    private final CookieManager cookieManager;
    private final int maxRedirects;
    private final long maxResponseBytes;

    public CapabilityBuiltinToolWebFetch(
            AgentRuntimeProperties properties,
            FetchRetryPolicy retryPolicy,
            BrowserProfileManager profileManager,
            JsRenderer jsRenderer) {
        this.defaultReadTimeoutSeconds = (int) properties.getTool().getWebFetch().getReadTimeout().getSeconds();
        this.retryPolicy = retryPolicy;
        this.profileManager = profileManager;
        this.jsRenderer = jsRenderer;

        var advancedConfig = properties.getTool().getWebFetchAdvanced();
        this.maxRedirects = Math.max(1, advancedConfig.getMaxRedirects());
        this.maxResponseBytes = Math.max(1024, advancedConfig.getMaxResponseBytes());

        this.cookieManager = new CookieManager();
        cookieManager.setCookiePolicy(CookiePolicy.ACCEPT_ALL);

        this.httpClient = HttpClient.newBuilder()
                .cookieHandler(cookieManager)
                .connectTimeout(properties.getTool().getWebFetch().getConnectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    @Override
    public BuiltinToolEnum getToolType() { return BuiltinToolEnum.WEB_SEARCH; }

    @Override
    public boolean needConfig() { return true; }

    @Override
    public ToolInfoVO getInfo() {
        ToolInfoVO info = new ToolInfoVO();
        info.setName(BuiltinToolConstants.NAME_PREFIX + CapabilityBuiltinToolWebFetch.class.getSimpleName());
        info.setDescription(DESCRIPTION);
        info.setDisplayNameCn("网页获取");
        info.setDisplayNameEn("Web Fetch");
        info.setParamSchema(ToolSchemaUtil.generateParamSchema(WebFetchExecuteContext.class));
        info.setResponseSchema(ToolSchemaUtil.generateParamSchema(WebFetchResultVO.class));
        return info;
    }

    @Override
    public Class<? extends ExecuteContext> getContextType() { return WebFetchExecuteContext.class; }

    @Override
    public Class<? extends ExecuteResult> getResultType() { return WebFetchResultVO.class; }

    @Override
    public ExecuteResult execute(ExecuteContext ctx) {
        WebFetchExecuteContext wfCtx = (WebFetchExecuteContext) ctx;
        String url = wfCtx.getUrl();
        int timeout = wfCtx.getTimeoutSeconds() > 0 ? wfCtx.getTimeoutSeconds() : defaultReadTimeoutSeconds;
        return fetchWithFallback(url, timeout);
    }

    /**
     * 三层 fallback 获取操作
     * Layer 1: HTTP 增强（UA 轮转 + 完整 headers + 重试）
     * Layer 2: JS 渲染（HtmlUnit）
     */
    private WebFetchResultVO fetchWithFallback(String url, int timeout) {
        // Layer 1: HTTP 增强
        WebFetchResultVO result = fetchWithRetry(url, timeout);
        if (result != null && result.getStatusCode() >= 200 && result.getStatusCode() < 300) {
            log.debug("HTTP fetch succeeded for url: {}", url);
            return result;
        }

        // Layer 2: JS 渲染
        log.warn("HTTP fetch failed for url: {}, trying JS rendering", url);
        result = jsRenderer.render(url, timeout);
        if (result != null && result.getStatusCode() >= 200 && result.getStatusCode() < 300) {
            log.debug("JS rendering succeeded for url: {}", url);
            return result;
        }

        // 所有方式都失败，返回最后的错误结果
        log.error("All fetch methods failed for url: {}", url);
        WebFetchResultVO errorResult = new WebFetchResultVO();
        errorResult.setStatusCode(500);
        errorResult.setContent("");
        errorResult.setContentType("");
        errorResult.setErrorMessage("Failed to fetch URL after HTTP retries and JS rendering");
        return errorResult;
    }

    /**
     * 带重试和 UA 轮转的 HTTP 获取
     */
    private WebFetchResultVO fetchWithRetry(String url, int timeout) {
        Exception lastException = null;

        for (int attempt = 0; attempt <= retryPolicy.getMaxRetries(); attempt++) {
            try {
                BrowserProfile profile = attempt == 0 ? profileManager.next() : profileManager.nextDifferent();
                log.debug("Fetching URL: {} (attempt {}/{}) with UA: {}", url, attempt + 1,
                        retryPolicy.getMaxRetries() + 1, profile.userAgent().substring(0, 50));
                return performFetch(url, timeout, profile);
            } catch (Exception e) {
                lastException = e;
                if (retryPolicy.shouldRetry(attempt, e)) {
                    log.warn(retryPolicy.getRetryLogMessage(attempt, e));
                    retryPolicy.waitBeforeRetry(attempt);
                } else {
                    log.error("Web fetch failed (no retry): {}", url, e);
                    break;
                }
            }
        }

        WebFetchResultVO result = new WebFetchResultVO();
        result.setStatusCode(500);
        result.setContent("Fetch failed after " + (retryPolicy.getMaxRetries() + 1) + " attempts");
        result.setContentType("");
        result.setErrorMessage(retryPolicy.getMaxRetriesExceededMessage(lastException));
        log.error("Web fetch failed after all retries: {}", url);
        return result;
    }

    /**
     * 执行单次获取操作，手动处理重定向，携带完整的浏览器指纹
     */
    private WebFetchResultVO performFetch(String url, int timeout, BrowserProfile profile) throws Exception {
        return performFetchWithRedirect(url, timeout, profile, 0);
    }

    private WebFetchResultVO performFetchWithRedirect(String url, int timeout, BrowserProfile profile, int redirectCount) throws Exception {
        if (redirectCount > maxRedirects) {
            throw new IOException("Too many redirects (max " + maxRedirects + ")");
        }

        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(timeout))
                .GET();

        // 应用浏览器配置中的所有 headers
        for (var entry : profile.headers().entrySet()) {
            builder.header(entry.getKey(), entry.getValue());
        }

        HttpResponse<String> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());

        // 检查响应体大小上限
        var contentLength = response.headers().firstValue("Content-Length");
        if (contentLength.isPresent()) {
            long len = Long.parseLong(contentLength.get());
            if (len > maxResponseBytes) {
                throw new IOException("Response too large: " + len + " bytes (max " + maxResponseBytes + ")");
            }
        }

        if (isRedirect(response.statusCode())) {
            String location = response.headers().firstValue("Location").orElse(null);
            if (location == null) {
                throw new IOException("Redirect " + response.statusCode() + " with no Location header");
            }
            URI redirectUri = URI.create(url).resolve(location);
            log.debug("Following redirect {} -> {}", url, redirectUri);
            return performFetchWithRedirect(redirectUri.toString(), timeout, profile, redirectCount + 1);
        }

        WebFetchResultVO result = new WebFetchResultVO();
        result.setStatusCode(response.statusCode());
        result.setContent(response.body());
        result.setContentType(response.headers().firstValue(HEADER_CONTENT_TYPE).orElse(""));
        return result;
    }

    private boolean isRedirect(int statusCode) {
        return statusCode == 301 || statusCode == 302 || statusCode == 303
                || statusCode == 307 || statusCode == 308;
    }
}
