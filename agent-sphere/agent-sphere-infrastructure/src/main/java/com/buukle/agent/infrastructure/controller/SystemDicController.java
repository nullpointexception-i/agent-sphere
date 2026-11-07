package com.buukle.agent.infrastructure.controller;



import com.buukle.agent.common.constant.ApiPrefix;
import com.buukle.agent.common.constant.PermissionCodes;
import com.buukle.agent.common.annotation.AuditLog;
import com.buukle.agent.common.annotation.RequirePermission;
import com.buukle.agent.common.dict.SystemDicConstants;
import com.buukle.agent.common.dict.SystemDicSpi;
import com.buukle.agent.common.util.BaseController;
import com.buukle.agent.infrastructure.controller.dtvo.SystemDicSaveDTO;
import com.buukle.agent.infrastructure.service.SystemDicService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(ApiPrefix.V1)
@RequiredArgsConstructor
public class SystemDicController extends BaseController {

    private final SystemDicSpi systemDicSpi;
    private final SystemDicService systemDicService;

    /** Active options for authenticated business pages (workspace / super-agent forms). */
    @GetMapping("/system/dict")
    public ResponseEntity<?> listActive(
            @RequestParam(defaultValue = SystemDicConstants.TYPE_POSITION) String type) {
        return ok(systemDicSpi.listActive(type));
    }

    @RequirePermission(PermissionCodes.ADMIN_DICTIONARY_READ)
    @GetMapping("/admin/dictionaries")
    public ResponseEntity<?> list(@RequestParam(required = false) String type,
                                  @RequestParam(required = false) String keyword) {
        return ok(systemDicService.list(type, keyword));
    }

    @AuditLog(action = "CREATE", resourceType = "SystemDic", resourceId = "#result?.body?.id")
    @RequirePermission(PermissionCodes.ADMIN_DICTIONARY_CREATE)
    @PostMapping("/admin/dictionaries")
    public ResponseEntity<?> create(@Valid @RequestBody SystemDicSaveDTO dto) {
        return created(systemDicService.create(dto));
    }

    @AuditLog(action = "UPDATE", resourceType = "SystemDic", resourceId = "#id")
    @RequirePermission(PermissionCodes.ADMIN_DICTIONARY_UPDATE)
    @PutMapping("/admin/dictionaries/{id}")
    public ResponseEntity<?> update(@PathVariable Long id,
                                    @Valid @RequestBody SystemDicSaveDTO dto) {
        return ok(systemDicService.update(id, dto));
    }

    @AuditLog(action = "DELETE", resourceType = "SystemDic", resourceId = "#id")
    @RequirePermission(PermissionCodes.ADMIN_DICTIONARY_DELETE)
    @DeleteMapping("/admin/dictionaries/{id}")
    public ResponseEntity<?> delete(@PathVariable Long id) {
        systemDicService.delete(id);
        return deleted();
    }
}
