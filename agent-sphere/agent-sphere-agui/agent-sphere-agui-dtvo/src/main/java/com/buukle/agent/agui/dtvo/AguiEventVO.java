package com.buukle.agent.agui.dtvo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class AguiEventVO implements Serializable {
    private String name;
    private String data;
}