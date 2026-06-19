package com.buukle.agent.model.dtvo.dto.complete;

import java.io.Serializable;
import lombok.Data;
import lombok.experimental.Accessors;

@Data
@Accessors(chain = true)
public class ThinkingDTO implements Serializable {
    private String type;
}
