package com.buukle.agent.instance.dtvo.vo;

import lombok.Data;
import java.io.Serializable;

@Data
public class UserVO implements Serializable {
    private Long id;
    private String username;
    private String displayName;
    private String englishName;
    private String email;
    private String avatar;
    private String token;
    private String status;
    private String superAdmin;
}
