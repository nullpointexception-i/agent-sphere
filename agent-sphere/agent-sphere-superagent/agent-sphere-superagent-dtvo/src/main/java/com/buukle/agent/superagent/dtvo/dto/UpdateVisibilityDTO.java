package com.buukle.agent.superagent.dtvo.dto;

import lombok.Data;

import java.io.Serializable;

@Data
public class UpdateVisibilityDTO implements Serializable {
    private boolean publicVisible;
}
