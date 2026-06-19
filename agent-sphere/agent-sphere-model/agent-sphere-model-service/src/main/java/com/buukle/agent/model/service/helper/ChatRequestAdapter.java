package com.buukle.agent.model.service.helper;

import com.buukle.agent.model.dtvo.dto.complete.ChatCompletionRequestDTO;
import org.springframework.stereotype.Component;

@Component
public class ChatRequestAdapter {

    public void adapt(ChatCompletionRequestDTO request, String company) {
        // reserved for common diff of company
    }
}
