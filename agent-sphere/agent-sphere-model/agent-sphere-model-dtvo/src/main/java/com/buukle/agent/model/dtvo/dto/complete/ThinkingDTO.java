package com.buukle.agent.model.dtvo.dto.complete;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serializable;

@Data
@Accessors(chain = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ThinkingDTO implements Serializable {
    private String type;
}
