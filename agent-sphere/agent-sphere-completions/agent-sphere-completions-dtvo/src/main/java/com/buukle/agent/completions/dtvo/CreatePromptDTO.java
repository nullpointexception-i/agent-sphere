package com.buukle.agent.completions.dtvo;

import lombok.Data;

import java.io.Serializable;

@Data
public class CreatePromptDTO implements Serializable {
    private String promptSystem;
    private String promptUser;
}
