package com.buukle.agent.im.service;

import com.buukle.agent.im.dtvo.dto.ResolveSessionInputDTO;

/** User responses to Router parked-input cards shown in an IM conversation. */
public interface ImSessionInputService {

    /** Submits per-call decisions for the conversation's pending tool-approval card. */
    void resolveApproval(Long conversationId, String inputId, ResolveSessionInputDTO dto);
}
