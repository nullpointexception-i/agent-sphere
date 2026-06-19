package com.buukle.agent.runtime.kernel.contract;

import lombok.Data;
import java.io.Serializable;

@Data
public class FunctionCallContract implements Serializable {
    private String name;
    private String arguments;
}
