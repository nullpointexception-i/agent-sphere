package com.buukle.agent.project.dtvo;

import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

@Data
public class CreateRequirementCommentDTO {
    @Size(max = 4000)
    private String content;
    private List<RequirementAttachmentVO> attachments;
}
