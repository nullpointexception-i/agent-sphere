package com.buukle.agent.instance.dtvo.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class UpdateProfileDTO {
    @NotBlank
    @Size(min = 1, max = 64)
    private String displayName;
    @NotBlank
    @Size(min = 1, max = 64)
    @Pattern(regexp = "^[a-zA-Z ]*$", message = "English name must be letters or spaces only")
    private String englishName;
    @Size(max = 3000000)
    private String avatar;
}
