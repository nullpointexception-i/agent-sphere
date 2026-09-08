package com.buukle.agent.instance.dtvo.dto;

import com.buukle.agent.instance.dtvo.vo.RunAttachment;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serializable;
import java.util.List;

@Data
public class CreateRunDTO implements Serializable {
    @NotNull(message = "sessionId can't be null")
    private Long sessionId;
    @NotBlank(message = "type can't be blank")
    @Size(min = 1, max = 64)
    private String type;
    @NotBlank(message = "userMessage can't be blank")
    @Size(min = 1, max = 5000)
    private String userMessage;
    private Boolean noClarification;

    /** 聊天附件引用（图片）：发送侧校验通过后随创建落库，Timeline 读取侧据此回显。 */
    private List<RunAttachment> attachments;
}
