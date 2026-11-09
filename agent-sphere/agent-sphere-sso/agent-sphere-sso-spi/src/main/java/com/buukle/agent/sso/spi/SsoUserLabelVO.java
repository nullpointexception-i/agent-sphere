package com.buukle.agent.sso.spi;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/** Display label of one user's external identity: providerCode + external username. */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SsoUserLabelVO implements Serializable {
    private Long userId;
    private String providerCode;
    private String subject;
}
