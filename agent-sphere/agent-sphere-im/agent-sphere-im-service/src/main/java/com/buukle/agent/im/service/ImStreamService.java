package com.buukle.agent.im.service;

import com.buukle.agent.im.dtvo.vo.ImStreamEvent;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

public interface ImStreamService {

    SseEmitter register(Long userId);

    void publish(Long userId, ImStreamEvent event);
}
