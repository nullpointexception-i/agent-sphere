package com.buukle.agent.bootstrap.controller;

import com.buukle.agent.runtime.kernel.async.FiberSet;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertTrue;

class FiberSetTest {

    @Test
    void awaitAll_cancellation_shouldInterruptRunningTask() {
        FiberSet fibers = new FiberSet(2, Duration.ofSeconds(5), Duration.ofSeconds(30));
        AtomicBoolean started = new AtomicBoolean(false);
        AtomicBoolean interrupted = new AtomicBoolean(false);

        fibers.submit("t1", () -> {
            started.set(true);
            try {
                Thread.sleep(60_000);
            } catch (InterruptedException e) {
                interrupted.set(true);
                throw new RuntimeException("interrupted");
            }
            return "done";
        });

        // 等待任务真正开始执行后，传入返回 true 的取消检查器
        while (!started.get()) {
            Thread.onSpinWait();
        }

        ConcurrentHashMap<String, String> results = fibers.awaitAll(() -> started.get());

        assertTrue(interrupted.get(), "cancellation should interrupt the running tool thread");
        assertTrue(results.isEmpty(), "interrupted task should not produce a result");
    }
}
