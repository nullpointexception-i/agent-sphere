package com.buukle.agent.model.dtvo.dto.complete;

import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serializable;

@Data
@Accessors(chain = true)
public class ThinkingDTO implements Serializable {
    private String type;
}
