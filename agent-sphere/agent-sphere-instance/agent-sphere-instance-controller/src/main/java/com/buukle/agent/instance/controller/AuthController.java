package com.buukle.agent.instance.controller;



import com.buukle.agent.common.constant.ApiPrefix;
import com.buukle.agent.common.constant.PermissionCodes;
import com.buukle.agent.common.annotation.AuditLog;
import com.buukle.agent.common.annotation.RequirePermission;
import com.buukle.agent.common.context.AuthContext;
import com.buukle.agent.common.context.SessionSurfaceHolder;
import com.buukle.agent.common.context.SessionSurfaceResolver;
import com.buukle.agent.common.context.TenantContext;
import com.buukle.agent.common.context.TenantScopeHolder;
import com.buukle.agent.common.error.CommonErrorCode;
import com.buukle.agent.common.exception.BizException;
import com.buukle.agent.common.util.BaseController;
import com.buukle.agent.common.util.RequestUtils;
import com.buukle.agent.instance.dtvo.dto.LoginDTO;
import com.buukle.agent.instance.dtvo.dto.RegisterDTO;
import com.buukle.agent.instance.dtvo.dto.UpdatePasswordDTO;
import com.buukle.agent.instance.dtvo.dto.UpdateProfileDTO;
import com.buukle.agent.instance.dtvo.vo.UserVO;
import com.buukle.agent.instance.service.UserService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping(ApiPrefix.V1 + "/auth")
@RequiredArgsConstructor
public class AuthController extends BaseController {
    private final UserService userService;

    private Long getCurrentUserId() {
        UserVO user = userService.getCurrentUser();
        if (user == null) throw new BizException(CommonErrorCode.UNAUTHORIZED);
        return user.getId();
    }

    @AuditLog(action = "LOGIN", resourceType = "User", resourceId = "#result?.body?.id")
    @PostMapping("/login")
    public ResponseEntity<?> login(@Valid @RequestBody LoginDTO dto, HttpServletRequest request) {
        // The login endpoint is in SKIP_PATHS, so the interceptor does not resolve the surface;
        // set it explicitly here for session issuance
        SessionSurfaceHolder.set(
                SessionSurfaceResolver.fromHeader(request.getHeader(SessionSurfaceHolder.HEADER)));
        try {
            dto.setLoginIp(RequestUtils.resolveClientIp(request));
            dto.setUserAgent(RequestUtils.resolveUserAgent(request));
            return ok(userService.login(dto));
        } finally {
            // Standalone tests bypass the interceptor, so clean up here to keep the surface
            // from leaking into subsequent tests/requests
            SessionSurfaceHolder.clear();
            TenantScopeHolder.clear();
            TenantContext.clear();
        }
    }

    @AuditLog(action = "REGISTER", resourceType = "User", resourceId = "#result?.body?.id")
    @PostMapping("/register")
    public ResponseEntity<?> register(@Valid @RequestBody RegisterDTO dto, HttpServletRequest request) {
        SessionSurfaceHolder.set(
                SessionSurfaceResolver.fromHeader(request.getHeader(SessionSurfaceHolder.HEADER)));
        try {
            dto.setLoginIp(RequestUtils.resolveClientIp(request));
            dto.setUserAgent(RequestUtils.resolveUserAgent(request));
            return ok(userService.register(dto));
        } finally {
            SessionSurfaceHolder.clear();
            TenantScopeHolder.clear();
            TenantContext.clear();
        }
    }

    @GetMapping("/check-username")
    public ResponseEntity<?> checkUsername(@RequestParam String username) {
        return ok(userService.checkUsername(username));
    }

    @GetMapping("/me")
    public ResponseEntity<?> me() {
        UserVO user = userService.getCurrentUser();
        if (user != null) {
            // The token is no longer stored on agent_user; return the token used by the current
            // request so the frontend still works after a refresh
            user.setToken(AuthContext.getToken());
        }
        return ok(user);
    }

    @AuditLog(action = "LOGOUT", resourceType = "User")
    @PostMapping("/logout")
    public ResponseEntity<?> logout() {
        userService.logout();
        return ok();
    }

    @AuditLog(action = "LOGOUT_ALL", resourceType = "User")
    @PostMapping("/logout-all")
    public ResponseEntity<?> logoutAll() {
        userService.logoutAll();
        return ok();
    }

    @GetMapping("/sessions")
    public ResponseEntity<?> sessions() {
        return ok(userService.listSessions());
    }

    @AuditLog(action = "REVOKE_SESSION", resourceType = "User", resourceId = "#sessionId")
    @DeleteMapping("/sessions/{sessionId}")
    public ResponseEntity<?> revokeSession(@PathVariable Long sessionId) {
        userService.revokeSession(sessionId);
        return ok();
    }

    @AuditLog(action = "UPDATE_PROFILE", resourceType = "User")
    @RequirePermission(PermissionCodes.USER_PROFILE_UPDATE)
    @PutMapping("/profile")
    public ResponseEntity<?> updateProfile(@Valid @RequestBody UpdateProfileDTO dto) {
        userService.updateProfile(getCurrentUserId(), dto.getDisplayName(), dto.getEnglishName(), dto.getAvatar());
        return ok();
    }

    @AuditLog(action = "UPDATE_PASSWORD", resourceType = "User")
    @RequirePermission(PermissionCodes.USER_PASSWORD_UPDATE)
    @PutMapping("/password")
    public ResponseEntity<?> updatePassword(@Valid @RequestBody UpdatePasswordDTO dto) {
        userService.updatePassword(getCurrentUserId(), dto.getOldPassword(), dto.getNewPassword());
        return ok();
    }
}
