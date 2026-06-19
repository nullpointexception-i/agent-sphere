package com.buukle.agent.runtime.kernel.contract;

import java.util.List;

public record TurnResult(String content, List<TurnToolCall> toolCalls) {}
