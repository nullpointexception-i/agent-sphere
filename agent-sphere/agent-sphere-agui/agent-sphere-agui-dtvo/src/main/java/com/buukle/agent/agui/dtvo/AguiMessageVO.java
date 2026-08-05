package com.buukle.agent.agui.dtvo;

import lombok.Data;

import java.io.Serializable;

@Data
public class AguiMessageVO implements Serializable {
    private String id;
    private String role;
    private String content;
}
