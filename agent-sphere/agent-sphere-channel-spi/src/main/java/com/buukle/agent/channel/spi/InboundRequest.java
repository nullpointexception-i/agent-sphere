package com.buukle.agent.channel.spi;

import java.util.Map;

/** Raw inbound request (headers + body), built by the Controller. */
public record InboundRequest(Map<String, String> headers, String body) {

    public InboundRequest {
        headers = headers == null ? Map.of() : Map.copyOf(headers);
    }
}
