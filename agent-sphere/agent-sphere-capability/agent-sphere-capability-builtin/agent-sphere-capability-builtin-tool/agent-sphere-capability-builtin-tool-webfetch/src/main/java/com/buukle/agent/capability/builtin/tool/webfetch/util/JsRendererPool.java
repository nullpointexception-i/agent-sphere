package com.buukle.agent.capability.builtin.tool.webfetch.util;

import com.gargoylesoftware.htmlunit.BrowserVersion;
import com.gargoylesoftware.htmlunit.NicelyResynchronizingAjaxController;
import com.gargoylesoftware.htmlunit.WebClient;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * WebClient 连接池，复用 HtmlUnit 实例以减少创建开销
 */
@Slf4j
public class JsRendererPool {

    private final int maxWaitMillis;
    private final int backgroundJsTimeout;
    private final BlockingQueue<WebClient> pool;
    private final int maxPoolSize;
    private volatile boolean closed = false;

    public JsRendererPool(int maxWaitMillis, int backgroundJsTimeout, int maxPoolSize) {
        this.maxWaitMillis = maxWaitMillis;
        this.backgroundJsTimeout = backgroundJsTimeout;
        this.maxPoolSize = maxPoolSize;
        this.pool = new LinkedBlockingQueue<>(maxPoolSize);
    }

    /**
     * 获取 WebClient 实例
     */
    public WebClient acquire(int timeout) throws InterruptedException {
        if (closed) return null;

        WebClient client = pool.poll(timeout, TimeUnit.SECONDS);
        if (client != null) return client;

        // 池中无空闲实例，创建新的
        if (pool.size() < maxPoolSize) {
            return createWebClient();
        }

        // 池满了，等待
        return pool.poll(maxWaitMillis, TimeUnit.MILLISECONDS);
    }

    /**
     * 归还 WebClient 实例
     */
    public void release(WebClient webClient) {
        if (closed || webClient == null) return;
        if (!pool.offer(webClient)) {
            // 池已满，关闭实例
            webClient.close();
        }
    }

    /**
     * 等待后台 JS 执行完成
     */
    public void waitForBackgroundJs(WebClient webClient) {
        webClient.waitForBackgroundJavaScript(backgroundJsTimeout);
    }

    /**
     * 创建 WebClient 实例
     */
    private WebClient createWebClient() {
        WebClient webClient = new WebClient(BrowserVersion.CHROME);
        webClient.getOptions().setJavaScriptEnabled(true);
        webClient.getOptions().setCssEnabled(false);
        webClient.getOptions().setThrowExceptionOnScriptError(false);
        webClient.getOptions().setThrowExceptionOnFailingStatusCode(false);
        webClient.getOptions().setPrintContentOnFailingStatusCode(false);
        webClient.getOptions().setRedirectEnabled(true);
        webClient.getOptions().setTimeout(15000);
        webClient.setAjaxController(new NicelyResynchronizingAjaxController());
        log.debug("Created new WebClient instance, pool size: {}/{}", pool.size() + 1, maxPoolSize);
        return webClient;
    }

    /**
     * 关闭所有实例
     */
    public void close() {
        closed = true;
        WebClient client;
        while ((client = pool.poll()) != null) {
            try {
                client.close();
            } catch (Exception e) {
                log.warn("Error closing WebClient", e);
            }
        }
    }
}
