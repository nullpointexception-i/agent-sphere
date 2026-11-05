package com.buukle.agent.instance.dtvo.vo;

import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.List;

/** Safe user fields exposed to the C-end workspace contact list. */
@Data
public class WorkspaceContactVO implements Serializable {
    private Long id;
    private String username;
    private String displayName;
    private String englishName;
    private String avatar;
    private String status;
    private List<String> roles;
    private LocalDateTime lastLoginAt;
}
