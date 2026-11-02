package com.buukle.agent.instance.dtvo.vo;

import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 签发结果：token 明文只在此处返回一次，服务端只保存摘要。
 */
@Data
public class IssuedTokenVO implements Serializable {
    private String token;
    private LocalDateTime expiresAt;
}
