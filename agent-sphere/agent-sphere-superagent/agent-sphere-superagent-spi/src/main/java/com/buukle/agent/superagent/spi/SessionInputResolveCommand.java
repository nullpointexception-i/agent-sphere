package com.buukle.agent.superagent.spi;

import java.io.Serializable;

/** Terminal transition of a previously projected Router input. */
public record SessionInputResolveCommand(
        String inputType,
        String inputId,
        String status
) implements Serializable {
}
