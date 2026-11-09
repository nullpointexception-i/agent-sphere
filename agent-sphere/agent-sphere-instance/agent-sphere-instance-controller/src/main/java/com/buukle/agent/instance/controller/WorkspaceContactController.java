package com.buukle.agent.instance.controller;


import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.buukle.agent.common.constant.ApiPrefix;
import com.buukle.agent.common.context.WithTenant;
import com.buukle.agent.common.util.BaseController;
import com.buukle.agent.instance.dtvo.vo.WorkspaceContactVO;
import com.buukle.agent.instance.spi.UserSpi;
import com.buukle.agent.sso.spi.SsoIdentitySpi;
import com.buukle.agent.sso.spi.SsoUserLabelVO;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Read-only user directory for the C-end workspace. */
@RestController
@RequestMapping(ApiPrefix.V1 + "/workspace/contacts")
@RequiredArgsConstructor
@WithTenant
public class WorkspaceContactController extends BaseController {

    private final UserSpi userSpi;
    private final SsoIdentitySpi ssoIdentitySpi;

    @GetMapping("/users")
    public ResponseEntity<?> users(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String keyword) {
        Page<WorkspaceContactVO> result = userSpi.pageContacts(page, size, keyword);
        attachSsoLabels(result.getRecords());
        return ok(result);
    }

    private void attachSsoLabels(List<WorkspaceContactVO> records) {
        if (records == null || records.isEmpty()) {
            return;
        }
        List<Long> ids = records.stream()
                .map(WorkspaceContactVO::getId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        Map<Long, SsoUserLabelVO> labels = ssoIdentitySpi.labelsByUserIds(ids);
        for (WorkspaceContactVO record : records) {
            SsoUserLabelVO label = labels.get(record.getId());
            if (label == null) {
                continue;
            }
            record.setSsoProviderCode(label.getProviderCode());
            record.setSsoSubject(label.getSubject());
            // Workspace lists share the console label (provider@external username).
            record.setDisplayName(label.getProviderCode() + "@" + label.getSubject());
        }
    }
}
