package com.buukle.agent.common.chrome;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

public class ChromePendingStore {

    private static final ConcurrentHashMap<String, CompletableFuture<ChromeCallbackDTO>> pending = new ConcurrentHashMap<>();

    public static void put(String commandId, CompletableFuture<ChromeCallbackDTO> future) {
        pending.put(commandId, future);
    }

    public static void complete(String commandId, ChromeCallbackDTO result) {
        CompletableFuture<ChromeCallbackDTO> future = pending.remove(commandId);
        if (future != null) {
            future.complete(result);
        }
    }

    public static CompletableFuture<ChromeCallbackDTO> remove(String commandId) {
        return pending.remove(commandId);
    }

    public static boolean contains(String commandId) {
        return pending.containsKey(commandId);
    }
}
