package com.buukle.agent.runtime.kernel.async;

import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.*;
import java.util.function.Consumer;

@Slf4j
public class FiberSet implements AutoCloseable {

    private static final long POLL_INTERVAL_NANOS = 500_000_000L; // 500ms
    /** 取消时等待被中断工具线程退出的界内时长（防止取消键被清空后孤儿子 Agent 空窗续跑）。 */
    private static final long CANCEL_JOIN_NANOS = TimeUnit.SECONDS.toNanos(10);

    private final Semaphore semaphore;
    private final ConcurrentLinkedQueue<CompletableFuture<FiberResult>> futures = new ConcurrentLinkedQueue<>();
    private final ConcurrentHashMap<CompletableFuture<FiberResult>, Thread> runningThreads = new ConcurrentHashMap<>();
    private final long submitTimeoutSeconds;
    private final long executionTimeoutSeconds;
    private Consumer<SingleResult> onResult;
    private volatile boolean cancelled; // 显式取消（检查器触发）：整批结果不再采集

    @FunctionalInterface
    public interface CancellationChecker {
        boolean isCancelled();
    }

    public FiberSet(int maxParallel, Duration submitTimeout, Duration executionTimeout) {
        this.semaphore = new Semaphore(maxParallel);
        this.submitTimeoutSeconds = submitTimeout.getSeconds();
        this.executionTimeoutSeconds = executionTimeout.getSeconds();
    }

    public void onEachResult(Consumer<SingleResult> listener) {
        this.onResult = listener;
    }

    public void submit(String callId, java.util.function.Supplier<String> task) {
        try {
            if (!semaphore.tryAcquire(submitTimeoutSeconds, TimeUnit.SECONDS)) {
                log.warn("Tool submission timed out for call {}", callId);
                futures.add(CompletableFuture.completedFuture(
                        new FiberResult(callId, null, "Tool submission timeout (" + submitTimeoutSeconds + "s)")));
                return;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }
        CompletableFuture<FiberResult> future = new CompletableFuture<>();
        // 虚拟线程 + 线程映射：取消时既能 cancel future，也能 interrupt 正在执行的线程
        // （CompletableFuture.cancel(true) 不会中断已在运行的任务）
        Thread worker = Thread.ofVirtual().start(() -> {
            try {
                String result = task.get();
                if (onResult != null) onResult.accept(new SingleResult(callId, result, null));
                future.complete(new FiberResult(callId, result, null));
            } catch (Exception e) {
                if (onResult != null) onResult.accept(new SingleResult(callId, null, e.getMessage()));
                future.complete(new FiberResult(callId, null, e.getMessage()));
            } finally {
                runningThreads.remove(future);
                semaphore.release();
            }
        });
        runningThreads.put(future, worker);
        futures.add(future);
    }

    public ConcurrentHashMap<String, String> awaitAll() {
        return awaitAll(null);
    }

    public ConcurrentHashMap<String, String> awaitAll(CancellationChecker checker) {
        CompletableFuture<?>[] arr = futures.toArray(new CompletableFuture[0]);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(executionTimeoutSeconds);
        try {
            while (true) {
                if (checker != null && checker.isCancelled()) {
                    cancelled = true;
                    interruptRunning();
                    // 先 cancel：被中断任务即便之后返回，也无法把结果写入已取消的 future；
                    // 再有界 join，等待工具线程真正退出，避免取消键被主循环收口清空后孤儿子 Agent 空窗续跑。
                    for (var f : futures) f.cancel(true);
                    awaitRunningTerminated();
                    break;
                }
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) throw new TimeoutException();
                try {
                    CompletableFuture.allOf(arr).get(
                            Math.min(remaining, POLL_INTERVAL_NANOS), TimeUnit.NANOSECONDS);
                    break;
                } catch (TimeoutException e) {
                    // Check cancellation at next poll interval or propagate real timeout
                    if (remaining <= POLL_INTERVAL_NANOS) throw e;
                }
            }
        } catch (TimeoutException e) {
            interruptRunning();
            for (var f : futures) f.cancel(true);
            log.warn("Fiber batch timed out after {}s", executionTimeoutSeconds);
        } catch (Exception e) {
            log.warn("Fiber batch failed: {}", e.getMessage());
        }

        ConcurrentHashMap<String, String> results = new ConcurrentHashMap<>();
        if (cancelled) {
            // 显式取消：整批结果已无意义（主循环 break 后不再使用），且避免“被中断任务”残留结果
            return results;
        }
        for (CompletableFuture<FiberResult> future : futures) {
            try {
                FiberResult fr = future.getNow(null);
                if (fr == null) continue;
                if (fr.error != null) {
                    results.put(fr.callId, "{\"error\":\"" + fr.error + "\"}");
                } else {
                    results.put(fr.callId, fr.result != null ? fr.result : "");
                }
            } catch (Exception e) {
                log.warn("Fiber failed: {}", e.getMessage());
            }
        }
        return results;
    }

    private void interruptRunning() {
        for (Map.Entry<CompletableFuture<FiberResult>, Thread> entry : runningThreads.entrySet()) {
            Thread t = entry.getValue();
            if (t != null && !entry.getKey().isDone()) {
                t.interrupt();
            }
        }
    }

    /**
     * 有界等待被中断的工具线程退出，再让主循环收口。
     * 若在中断后立即返回，孤儿子 Agent（skill）会唤醒后重查取消键——而取消键可能在收口时已被
     * 清空 → 空窗续跑。这里等它们真正终止：它们唤醒后重查时取消键仍在位 → 以 cancelled 退出。
     */
    private void awaitRunningTerminated() {
        long deadline = System.nanoTime() + CANCEL_JOIN_NANOS;
        boolean reInterrupted = false;
        while (!runningThreads.isEmpty()) {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) {
                log.warn("Fiber cancel join timed out after {}s; {} fiber(s) still running",
                        TimeUnit.NANOSECONDS.toSeconds(CANCEL_JOIN_NANOS), runningThreads.size());
                return;
            }
            try {
                Thread.sleep(Math.min(200, TimeUnit.NANOSECONDS.toMillis(remaining)));
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return;
            }
            // 过半时长后二次 interrupt，防止一次中断被阻塞点吞掉
            if (!reInterrupted && remaining < CANCEL_JOIN_NANOS / 2) {
                reInterrupted = true;
                interruptRunning();
            }
        }
    }

    @Override
    public void close() {
        interruptRunning();
        for (CompletableFuture<FiberResult> f : futures) {
            f.cancel(true);
        }
    }

    public record SingleResult(String callId, String result, String error) {
    }

    public record FiberResult(String callId, String result, String error) {
    }
}
