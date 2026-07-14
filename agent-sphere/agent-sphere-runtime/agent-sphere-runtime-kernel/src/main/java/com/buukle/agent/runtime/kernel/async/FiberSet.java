package com.buukle.agent.runtime.kernel.async;

import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.util.concurrent.*;
import java.util.function.Consumer;

@Slf4j
public class FiberSet implements AutoCloseable {

    private static final ExecutorService SHARED_EXECUTOR = Executors.newVirtualThreadPerTaskExecutor();
    private static final long POLL_INTERVAL_NANOS = 500_000_000L; // 500ms

    private final Semaphore semaphore;
    private final ConcurrentLinkedQueue<CompletableFuture<FiberResult>> futures = new ConcurrentLinkedQueue<>();
    private final long submitTimeoutSeconds;
    private final long executionTimeoutSeconds;
    private Consumer<SingleResult> onResult;

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
        futures.add(CompletableFuture.supplyAsync(() -> {
            try {
                String result = task.get();
                if (onResult != null) onResult.accept(new SingleResult(callId, result, null));
                return new FiberResult(callId, result, null);
            } catch (Exception e) {
                if (onResult != null) onResult.accept(new SingleResult(callId, null, e.getMessage()));
                return new FiberResult(callId, null, e.getMessage());
            } finally {
                semaphore.release();
            }
        }, SHARED_EXECUTOR));
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
                    for (var f : futures) f.cancel(true);
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
            for (var f : futures) f.cancel(true);
            log.warn("Fiber batch timed out after {}s", executionTimeoutSeconds);
        } catch (Exception e) {
            log.warn("Fiber batch failed: {}", e.getMessage());
        }

        ConcurrentHashMap<String, String> results = new ConcurrentHashMap<>();
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

    @Override
    public void close() {
        for (CompletableFuture<FiberResult> f : futures) {
            f.cancel(true);
        }
    }

    public record SingleResult(String callId, String result, String error) {
    }

    public record FiberResult(String callId, String result, String error) {
    }
}
