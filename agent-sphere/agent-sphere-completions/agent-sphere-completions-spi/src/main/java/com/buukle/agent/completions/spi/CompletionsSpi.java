package com.buukle.agent.completions.spi;

import com.buukle.agent.completions.dtvo.ChatCompletionsResp;
import com.buukle.agent.completions.dtvo.CompletionsInput;
import com.buukle.agent.sso.spi.CallerAuth;

/**
 * 单次 LLM 能力（completions）SPI，供外部/其他模块复用。
 */
public interface CompletionsSpi {
    ChatCompletionsResp execute(CallerAuth auth, CompletionsInput input);
}
