package com.buukle.agent.runtime.kernel.async;

import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.*;
import java.util.function.Consumer;

@Slf4j
public class FiberSet implements AutoCloseable {

    private static final long POLL_INTERVAL_NANOS = 500_000_000L; // 500ms
    /** Bounded duration to wait for interrupted tool threads to exit on cancel (prevents orphan sub-agents from resuming during the gap after the cancel key is cleared). */
    private static final long CANCEL_JOIN_NANOS = TimeUnit.SECONDS.toNanos(10);

    private final Semaphore semaphore;
    private final ConcurrentLinkedQueue<CompletableFuture<FiberResult>> futures = new ConcurrentLinkedQueue<>();
    private final ConcurrentHashMap<CompletableFuture<FiberResult>, Thread> runningThreads = new ConcurrentHashMap<>();
    private final long submitTimeoutSeconds;
    private final long executionTimeoutSeconds;
    private Consumer<SingleResult> onResult;
    private volatile boolean cancelled; // Explicit cancellation (triggered by the checker): stop collecting results for the whole batch

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
        // Virtual threads + thread mapping: on cancel we can both cancel the future and interrupt the running thread
        // (CompletableFuture.cancel(true) does not interrupt a task that is already running)
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
                    // Cancel first: even if an interrupted task returns later, it cannot write its result into the cancelled future;
                    // then join with a bound and wait for the tool threads to actually exit, so orphan sub-agents do not resume in the gap after the main loop's finalization clears the cancel key.
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
            // Explicit cancel: the whole batch of results is meaningless (unused after the main loop breaks), and this avoids leaving stale results from "interrupted tasks"
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
     * Waits with a bound for interrupted tool threads to exit, then lets the main loop finalize.
     * Returning immediately after the interrupt would let orphan sub-agents (skill) wake up and re-check the cancel key — but the key may already have been
     * cleared during finalization → they would resume in the gap. Here we wait for them to terminate for real: when they wake and re-check, the cancel key is still present → they exit as cancelled.
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
            // Second interrupt after half the duration, in case the first interrupt was swallowed by a blocking point
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
