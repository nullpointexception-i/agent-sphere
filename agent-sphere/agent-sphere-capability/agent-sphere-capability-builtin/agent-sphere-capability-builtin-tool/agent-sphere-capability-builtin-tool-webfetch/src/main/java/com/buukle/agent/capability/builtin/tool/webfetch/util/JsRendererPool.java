package com.buukle.agent.capability.builtin.tool.webfetch.util;

import com.gargoylesoftware.htmlunit.BrowserVersion;
import com.gargoylesoftware.htmlunit.NicelyResynchronizingAjaxController;
import com.gargoylesoftware.htmlunit.WebClient;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * WebClient connection pool that reuses HtmlUnit instances to reduce creation overhead
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
     * Acquires a WebClient instance
     */
    public WebClient acquire(int timeout) throws InterruptedException {
        if (closed) return null;

        WebClient client = pool.poll(timeout, TimeUnit.SECONDS);
        if (client != null) return client;

        // No idle instance in the pool, create a new one
        if (pool.size() < maxPoolSize) {
            return createWebClient();
        }

        // Pool is full, wait
        return pool.poll(maxWaitMillis, TimeUnit.MILLISECONDS);
    }

    /**
     * Returns a WebClient instance
     */
    public void release(WebClient webClient) {
        if (closed || webClient == null) return;
        if (!pool.offer(webClient)) {
            // Pool is full, close the instance
            webClient.close();
        }
    }

    /**
     * Waits for background JS to finish executing
     */
    public void waitForBackgroundJs(WebClient webClient) {
        webClient.waitForBackgroundJavaScript(backgroundJsTimeout);
    }

    /**
     * Creates a WebClient instance
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
     * Closes all instances
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
