package com.buukle.agent.completions.spi;

import com.buukle.agent.completions.dtvo.ChatCompletionsResp;
import com.buukle.agent.completions.dtvo.CompletionsInput;
import com.buukle.agent.sso.spi.CallerAuth;

/**
 * One-shot LLM capability (completions) SPI, reusable by external callers/other modules.
 */
public interface CompletionsSpi {
    ChatCompletionsResp execute(CallerAuth auth, CompletionsInput input);
}
