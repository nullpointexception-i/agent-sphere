package com.buukle.agent.superagent.dtvo.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.io.Serializable;

/** Streaming fragment from the desktop driver. */
@Data
public class LocalTaskChunkDTO implements Serializable {

    @NotBlank
    private String leaseToken;

    private Long seq;

    /** reply or thinking. */
    private String phase;

    private String delta;
}
