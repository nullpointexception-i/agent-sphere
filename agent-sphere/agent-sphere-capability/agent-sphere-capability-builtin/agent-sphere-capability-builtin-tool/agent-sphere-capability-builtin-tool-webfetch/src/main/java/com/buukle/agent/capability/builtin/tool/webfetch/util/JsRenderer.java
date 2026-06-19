package com.buukle.agent.capability.builtin.tool.webfetch.util;

import com.buukle.agent.capability.builtin.tool.webfetch.dtvo.vo.WebFetchResultVO;
import com.buukle.agent.common.config.AgentRuntimeProperties;
import com.gargoylesoftware.htmlunit.WebClient;
import com.gargoylesoftware.htmlunit.html.HtmlPage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class JsRenderer {

    private final JsRendererPool pool;
    private final boolean enabled;

    public JsRenderer(AgentRuntimeProperties properties) {
        AgentRuntimeProperties.ToolConfig.JsRenderConfig config = properties.getTool().getJsRender();
        this.enabled = config.isEnabled();
        this.pool = new JsRendererPool(config.getMaxWaitMillis(), config.getBackgroundJsTimeout(), config.getMaxPoolSize());
    }

    /**
     * 渲染 URL，获取 JS 执行后的完整 HTML
     */
    public WebFetchResultVO render(String url, int timeout) {
        if (!enabled) {
            log.debug("JS rendering is disabled");
            return null;
        }

        WebClient webClient = null;
        try {
            webClient = pool.acquire(timeout);
            if (webClient == null) {
                log.warn("Failed to acquire WebClient from pool");
                return null;
            }

            log.debug("Rendering URL with JS: {}", url);
            HtmlPage page = webClient.getPage(url);
            pool.waitForBackgroundJs(webClient);

            String html = page.asXml();
            String contentType = page.getWebResponse().getContentType();
            int statusCode = page.getWebResponse().getStatusCode();

            log.debug("JS rendering completed: url={}, status={}, size={}", url, statusCode, html.length());
            WebFetchResultVO result = new WebFetchResultVO();
            result.setStatusCode(statusCode);
            result.setContent(html);
            result.setContentType(contentType);
            return result;
        } catch (Exception e) {
            log.warn("JS rendering failed for url: {}", url, e);
            return null;
        } finally {
            if (webClient != null) {
                pool.release(webClient);
            }
        }
    }

    /**
     * 资源清理
     */
    public void close() {
        pool.close();
    }
}
