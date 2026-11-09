package com.buukle.agent.im.dtvo.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.io.Serializable;

@Data
public class TransferOwnerDTO implements Serializable {
    @NotNull
    private Long userId;
}
