package com.buukle.agent.channel.spi;

import java.io.Serializable;

/** Result of an outbound send. */
public record SendResult(boolean success, String externalMessageId, String error) implements Serializable {

    public static SendResult ok(String externalMessageId) {
        return new SendResult(true, externalMessageId, null);
    }

    public static SendResult failed(String error) {
        return new SendResult(false, null, error);
    }
}
