package com.buukle.agent.channel.spi;

import java.util.Map;

/** 入站原始请求（Header + body），由 Controller 构造。 */
public record InboundRequest(Map<String, String> headers, String body) {

    public InboundRequest {
        headers = headers == null ? Map.of() : Map.copyOf(headers);
    }
}
