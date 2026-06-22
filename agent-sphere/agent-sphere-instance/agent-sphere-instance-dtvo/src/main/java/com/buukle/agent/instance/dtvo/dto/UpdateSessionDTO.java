package com.buukle.agent.instance.dtvo.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serializable;

@Data
public class UpdateSessionDTO implements Serializable {
    @NotBlank(message = "title can't be blank")
    @Size(min = 1, max = 255)
    private String title;
}
