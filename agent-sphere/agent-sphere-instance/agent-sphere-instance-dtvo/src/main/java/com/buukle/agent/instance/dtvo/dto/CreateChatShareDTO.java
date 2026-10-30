package com.buukle.agent.instance.dtvo.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.io.Serializable;

@Data
public class CreateChatShareDTO implements Serializable {
    @NotNull
    private Long runId;
    private Integer runCount;
}
