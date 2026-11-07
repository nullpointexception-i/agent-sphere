package com.buukle.agent.instance.service.impl;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.buukle.agent.admin.spi.PermissionSpi;
import com.buukle.agent.admin.spi.RoleSpi;
import com.buukle.agent.admin.spi.TenantScopeSpi;
import com.buukle.agent.admin.dtvo.vo.SysRoleVO;
import com.buukle.agent.common.context.AuthContext;
import com.buukle.agent.common.context.SessionSurfaceHolder;
import com.buukle.agent.common.context.TenantContext;
import com.buukle.agent.common.context.TenantScope;
import com.buukle.agent.common.context.TenantScopeHolder;
import com.buukle.agent.common.error.CommonErrorCode;
import com.buukle.agent.common.event.UserRegisteredEvent;
import com.buukle.agent.common.exception.BizException;
import com.buukle.agent.common.util.AvatarGenerator;
import com.buukle.agent.common.util.TokenHasher;
import com.buukle.agent.instance.domain.AgentUser;
import com.buukle.agent.instance.dtvo.dto.LoginDTO;
import com.buukle.agent.instance.dtvo.dto.RegisterDTO;
import com.buukle.agent.instance.dtvo.enums.UserEnum;
import com.buukle.agent.instance.dtvo.vo.IssuedTokenVO;
import com.buukle.agent.instance.dtvo.vo.UserIdentityVO;
import com.buukle.agent.instance.dtvo.vo.UserRoleVO;
import com.buukle.agent.instance.dtvo.vo.UserSessionVO;
import com.buukle.agent.instance.dtvo.vo.UserVO;
import com.buukle.agent.instance.dtvo.vo.WorkspaceContactVO;
import com.buukle.agent.instance.exception.InstanceErrorCode;
import com.buukle.agent.instance.repository.UserMapper;
import com.buukle.agent.instance.service.UserService;
import com.buukle.agent.instance.spi.UserTokenSpi;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.security.MessageDigest;
import java.util.Collection;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class UserServiceImpl extends ServiceImpl<UserMapper, AgentUser> implements UserService {

    private final PasswordEncoder passwordEncoder;
    private final PermissionSpi permissionSpi;
    private final RoleSpi roleSpi;
    private final ApplicationEventPublisher eventPublisher;
    private final UserTokenSpi userTokenSpi;
    private final TenantScopeSpi tenantScopeSpi;

    public static String sha256(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(input.getBytes());
            return HexFormat.of().formatHex(digest);
        } catch (Exception e) {
            throw new BizException(CommonErrorCode.INTERNAL_ERROR, "SHA-256 error", e);
        }
    }

    /** Current request surface (CONSOLE / WIDGET / INTERNAL), written to the session row for device management. */
    private static String currentTokenSource() {
        return SessionSurfaceHolder.current().name();
    }

    private static final String DEFAULT_USER_ROLE_CODE = "USER";

    /** Resolve the initial roles for a new user inside the target tenant. */
    public static List<Long> resolveInitialRoleIds(RoleSpi roleSpi, Long defaultRoleId, Long tenantId) {
        if (defaultRoleId != null) {
            return List.of(defaultRoleId);
        }
        Long roleId = roleSpi.findIdByTenantAndCode(tenantId, DEFAULT_USER_ROLE_CODE);
        return roleId == null ? List.of() : List.of(roleId);
    }

    private UserVO toVO(AgentUser user) {
        UserVO vo = new UserVO();
        vo.setId(user.getId());
        vo.setUsername(user.getUsername());
        vo.setDisplayName(user.getDisplayName());
        vo.setEnglishName(user.getEnglishName());
        vo.setEmail(user.getEmail());
        vo.setAvatar(user.getAvatar());
        vo.setStatus(user.getStatus());
        vo.setLastLoginAt(user.getLastLoginAt());
        vo.setSuperAdmin(user.getSuperAdmin());
        Long tenantId = TenantScopeHolder.currentTenantId();
        vo.setCurrentTenantId(tenantId);
        // Platform administrators manage users across the tree; tenant
        // administrators only see roles in tenants they can manage.
        List<SysRoleVO> assignments = roleSpi.listAssignments(user.getId());
        List<SysRoleVO> effectiveRoles;
        if (AuthContext.isSuperAdmin()) {
            effectiveRoles = assignments;
        } else {
            TenantScope scope = TenantScopeHolder.get();
            Set<Long> visible = scope == null ? Set.of() : scope.visibleTenantIds();
            effectiveRoles = assignments.stream()
                    .filter(role -> role.getTenantId() != null
                            && visible.contains(role.getTenantId()))
                    .toList();
        }
        vo.setRoles(effectiveRoles.stream().map(SysRoleVO::getCode).toList());
        vo.setDemo(effectiveRoles.stream().anyMatch(r -> "DEMO".equals(r.getCode())));
        vo.setRoleDetails(effectiveRoles.stream()
                .map(role -> {
                    UserRoleVO detail = new UserRoleVO();
                    detail.setId(role.getId());
                    detail.setCode(role.getCode());
                    detail.setName(role.getName());
                    detail.setTenantId(role.getTenantId());
                    detail.setTenantName(role.getTenantName());
                    detail.setPlatformRole(role.getPlatformRole());
                    return detail;
                })
                .toList());
        boolean isSuperAdmin = UserEnum.IS_SUPER_ADMIN.equals(user.getSuperAdmin());
        vo.setPermissions(isSuperAdmin
                ? permissionSpi.listAllCodes()
                : permissionSpi.listCodesByUserId(user.getId(), tenantId));
        return vo;
    }

    private TenantScope applyTenantContext(AgentUser user) {
        TenantScope scope = tenantScopeSpi.resolve(user.getId(), user.getUsername());
        TenantContext.set(scope.currentTenantId());
        TenantScopeHolder.start(scope);
        return scope;
    }

    @Override
    public UserVO login(LoginDTO dto) {
        AgentUser user = lambdaQuery().eq(AgentUser::getUsername, dto.getUsername()).one();
        if (user == null) throw new BizException(InstanceErrorCode.INVALID_CREDENTIALS);

        if (passwordEncoder.matches(dto.getPassword(), user.getPassword())) {
            // BCrypt match — normal path
        } else if (user.getPassword().length() == 64 && sha256(dto.getPassword()).equals(user.getPassword())) {
            // SHA-256 legacy match → upgrade to bcrypt
            user.setPassword(passwordEncoder.encode(dto.getPassword()));
        } else {
            throw new BizException(InstanceErrorCode.INVALID_CREDENTIALS);
        }

        IssuedTokenVO issued = userTokenSpi.issue(
                user.getId(), currentTokenSource(), dto.getLoginIp(), dto.getUserAgent());

        user.setLastLoginAt(java.time.LocalDateTime.now());
        user.setLastLoginIp(dto.getLoginIp());
        user.setLastLoginUa(dto.getUserAgent());
        updateById(user);

        AuthContext.setToken(issued.getToken());
        AuthContext.setUserId(user.getId());
        AuthContext.setUsername(user.getUsername());
        AuthContext.setDisplayName(user.getDisplayName());
        AuthContext.setSuperAdmin(UserEnum.IS_SUPER_ADMIN.equals(user.getSuperAdmin()));
        applyTenantContext(user);

        UserVO vo = toVO(user);
        vo.setToken(issued.getToken());
        return vo;
    }

    @Override
    public UserVO getCurrentUser() {
        Long userId = AuthContext.getUserId();
        if (userId == null) return null;
        AgentUser user = getById(userId);
        return user != null ? toVO(user) : null;
    }

    @Override
    public void logout() {
        String token = AuthContext.getToken();
        if (token != null) {
            userTokenSpi.revoke(token);
        }
        TenantScopeHolder.clear();
        TenantContext.clear();
        AuthContext.clear();
    }

    @Override
    public void logoutAll() {
        Long userId = AuthContext.getUserId();
        if (userId != null) {
            userTokenSpi.revokeAll(userId);
        }
        TenantScopeHolder.clear();
        TenantContext.clear();
        AuthContext.clear();
    }

    @Override
    public List<UserSessionVO> listSessions() {
        Long userId = AuthContext.getUserId();
        if (userId == null) return List.of();
        String token = AuthContext.getToken();
        return userTokenSpi.listActive(userId, token == null ? null : TokenHasher.sha256(token));
    }

    @Override
    public void revokeSession(Long sessionId) {
        Long userId = AuthContext.getUserId();
        if (userId == null) throw new BizException(CommonErrorCode.UNAUTHORIZED);
        userTokenSpi.revokeById(userId, sessionId);
    }

    @Override
    public void updateProfile(Long userId, String displayName, String englishName, String avatar) {
        AgentUser user = getById(userId);
        if (user == null) throw new BizException(InstanceErrorCode.USER_NOT_FOUND);
        if (displayName != null) user.setDisplayName(displayName);
        if (englishName != null) user.setEnglishName(englishName);
        if (avatar != null) {
            byte[] decoded = java.util.Base64.getDecoder().decode(avatar.contains(",") ? avatar.split(",")[1] : avatar);
            if (decoded.length > 2 * 1024 * 1024) {
                throw new BizException(CommonErrorCode.PARAM_INVALID, "Avatar image too large, max 2MB");
            }
            user.setAvatar(avatar);
        }
        updateById(user);
    }

    @Override
    public void updatePassword(Long userId, String oldPassword, String newPassword) {
        AgentUser user = getById(userId);
        if (user == null) throw new BizException(InstanceErrorCode.USER_NOT_FOUND);
        boolean bcryptMatch = passwordEncoder.matches(oldPassword, user.getPassword());
        boolean sha256Match = user.getPassword().length() == 64 && sha256(oldPassword).equals(user.getPassword());
        if (!bcryptMatch && !sha256Match)
            throw new BizException(InstanceErrorCode.OLD_PASSWORD_MISMATCH);
        user.setPassword(passwordEncoder.encode(newPassword));
        updateById(user);
        // After a password change other devices must sign in again; the current device is kept so the
        // user is not logged out right after changing the password
        userTokenSpi.revokeAllExcept(userId, AuthContext.getToken());
    }

    @Override
    public boolean checkUsername(String username) {
        return lambdaQuery().eq(AgentUser::getUsername, username).count() == 0;
    }

    @Override
    public UserVO getByUserId(Long userId) {
        AgentUser user = getById(userId);
        return user != null ? toVO(user) : null;
    }

    @Override
    public UserVO loginByUserId(Long userId) {
        AgentUser user = getById(userId);
        if (user == null) throw new BizException(InstanceErrorCode.USER_NOT_FOUND);
        IssuedTokenVO issued = userTokenSpi.issue(userId, currentTokenSource(), null, null);
        user.setLastLoginAt(java.time.LocalDateTime.now());
        updateById(user);
        AuthContext.setToken(issued.getToken());
        AuthContext.setUserId(user.getId());
        AuthContext.setUsername(user.getUsername());
        AuthContext.setDisplayName(user.getDisplayName());
        AuthContext.setSuperAdmin(UserEnum.IS_SUPER_ADMIN.equals(user.getSuperAdmin()));
        applyTenantContext(user);
        UserVO vo = toVO(user);
        vo.setToken(issued.getToken());
        return vo;
    }

    @Override
    public UserVO register(RegisterDTO dto) {
        return register(dto, null);
    }

    @Override
    public UserVO register(RegisterDTO dto, Long defaultRoleId) {
        return register(dto, defaultRoleId, null);
    }

    @Override
    public UserVO register(RegisterDTO dto, Long defaultRoleId, Long tenantId) {
        if (!dto.getPassword().equals(dto.getRepeatPassword())) {
            throw new BizException(CommonErrorCode.PARAM_INVALID, "Passwords do not match");
        }
        if (!checkUsername(dto.getUsername())) {
            throw new BizException(InstanceErrorCode.USERNAME_TAKEN);
        }
        AgentUser user = new AgentUser();
        user.setUsername(dto.getUsername());
        user.setPassword(passwordEncoder.encode(dto.getPassword()));
        user.setDisplayName(dto.getUsername());
        user.setEnglishName(dto.getUsername());
        user.setAvatar(AvatarGenerator.generateUserBase64());
        user.setStatus(UserEnum.STATUS_ACTIVE);
        try {
            save(user);
        } catch (Exception e) {
            if (e.getMessage() != null && e.getMessage().contains("duplicate key")) {
                throw new BizException(InstanceErrorCode.USERNAME_TAKEN);
            }
            throw new BizException(InstanceErrorCode.REGISTER_FAILED);
        }
        // Self-registration goes to the default tenant; SSO provisioning goes to
        // the tenant bound to the identity provider.
        if (tenantId != null) {
            tenantScopeSpi.ensureAssignment(user.getId(), tenantId);
        } else {
            tenantScopeSpi.ensureDefaultAssignment(user.getId());
        }
        Long targetTenantId = tenantId != null ? tenantId : tenantScopeSpi.defaultTenantId();
        List<Long> initialRoleIds = resolveInitialRoleIds(roleSpi, defaultRoleId, targetTenantId);
        if (!initialRoleIds.isEmpty()) {
            roleSpi.assignRoles(user.getId(), initialRoleIds);
        }
        // Self-service registration (not SSO provisioning): publish an event to trigger the resource
        // initialization template (NativeUserResourceProvisioner)
        if (defaultRoleId == null) {
            UserRegisteredEvent event = new UserRegisteredEvent();
            event.setUserId(user.getId());
            event.setUsername(user.getUsername());
            eventPublisher.publishEvent(event);
        }
        // Auto-login
        IssuedTokenVO issued = userTokenSpi.issue(
                user.getId(), currentTokenSource(), dto.getLoginIp(), dto.getUserAgent());
        user.setLastLoginAt(java.time.LocalDateTime.now());
        user.setLastLoginIp(dto.getLoginIp());
        user.setLastLoginUa(dto.getUserAgent());
        updateById(user);
        AuthContext.setToken(issued.getToken());
        AuthContext.setUserId(user.getId());
        AuthContext.setUsername(user.getUsername());
        AuthContext.setDisplayName(user.getDisplayName());
        AuthContext.setSuperAdmin(UserEnum.IS_SUPER_ADMIN.equals(user.getSuperAdmin()));
        TenantScope scope = applyTenantContext(user);
        if (scope.currentTenantId() != null && !scope.currentTenantId().equals(user.getTenantId())) {
            user.setTenantId(scope.currentTenantId());
            updateById(user);
        }
        UserVO vo = toVO(user);
        vo.setToken(issued.getToken());
        return vo;
    }

    @Override
    public com.baomidou.mybatisplus.extension.plugins.pagination.Page<UserVO> listPage(int page, int size) {
        TenantScope scope = TenantScopeHolder.currentOrFallback();
        if (scope == null || scope.selfOnly()) {
            // SELF_ONLY means the user may only see rows they created. The admin
            // user directory is not owned-row data, and the current user is
            // excluded anyway, so the directory is empty for this visibility.
            return emptyUserPage(page, size);
        }
        List<Long> userIds = tenantScopeSpi.listUserIdsInTenants(scope.visibleTenantIds());
        if (userIds.isEmpty()) {
            return emptyUserPage(page, size);
        }
        Long currentUserId = AuthContext.getUserId();
        var mpPage = lambdaQuery()
                .in(AgentUser::getId, userIds)
                .ne(currentUserId != null, AgentUser::getId, currentUserId)
                .page(new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>(page, size));
        var voPage = new com.baomidou.mybatisplus.extension.plugins.pagination.Page<UserVO>(
                mpPage.getCurrent(), mpPage.getSize(), mpPage.getTotal());
        voPage.setRecords(mpPage.getRecords().stream().map(this::toVO).toList());
        return voPage;
    }

    private static com.baomidou.mybatisplus.extension.plugins.pagination.Page<UserVO> emptyUserPage(int page, int size) {
        var empty = new com.baomidou.mybatisplus.extension.plugins.pagination.Page<UserVO>(
                Math.max(1, page), Math.max(1, size), 0);
        empty.setRecords(List.of());
        return empty;
    }

    @Override
    public Page<WorkspaceContactVO> pageContacts(int page, int size, String keyword) {
        TenantScope scope = TenantScopeHolder.currentOrFallback();
        Long tenantId = TenantScopeHolder.currentTenantId();
        if (scope == null || tenantId == null) {
            return new Page<>(Math.max(1, page), Math.max(1, size), 0);
        }
        Long currentUserId = AuthContext.getUserId();
        // Workspace contacts are the user directory of the current tenant. The
        // data visibility scope must not shrink the candidate list for group
        // chats (SELF_ONLY would otherwise return only the caller).
        List<Long> userIds = tenantScopeSpi.listUserIdsInTenants(Set.of(tenantId));
        if (userIds.isEmpty()) {
            Page<WorkspaceContactVO> empty = new Page<>(Math.max(1, page), Math.max(1, size), 0);
            empty.setRecords(List.of());
            return empty;
        }
        var mpPage = lambdaQuery()
                .eq(AgentUser::getStatus, UserEnum.STATUS_ACTIVE)
                .in(AgentUser::getId, userIds)
                .ne(currentUserId != null, AgentUser::getId, currentUserId)
                .and(keyword != null && !keyword.isBlank(), w -> w
                        .like(AgentUser::getUsername, keyword)
                        .or().like(AgentUser::getDisplayName, keyword)
                        .or().like(AgentUser::getEnglishName, keyword))
                .orderByDesc(AgentUser::getLastLoginAt)
                .orderByDesc(AgentUser::getId)
                .page(new Page<>(Math.max(1, page), Math.max(1, size)));
        Page<WorkspaceContactVO> voPage = new Page<>(mpPage.getCurrent(), mpPage.getSize(), mpPage.getTotal());
        voPage.setRecords(mpPage.getRecords().stream().map(this::toContactVO).toList());
        return voPage;
    }

    @Override
    public Map<Long, UserIdentityVO> identityMap(Collection<Long> userIds) {
        if (userIds == null || userIds.isEmpty()) {
            return Map.of();
        }
        return lambdaQuery()
                .select(AgentUser::getId, AgentUser::getUsername, AgentUser::getDisplayName,
                        AgentUser::getEnglishName, AgentUser::getAvatar)
                .in(AgentUser::getId, userIds)
                .list()
                .stream()
                .collect(Collectors.toMap(AgentUser::getId, this::toIdentityVO, (left, right) -> left));
    }

    private UserIdentityVO toIdentityVO(AgentUser user) {
        UserIdentityVO vo = new UserIdentityVO();
        vo.setId(user.getId());
        vo.setUsername(user.getUsername());
        vo.setDisplayName(user.getDisplayName());
        vo.setEnglishName(user.getEnglishName());
        vo.setAvatar(user.getAvatar());
        return vo;
    }

    private WorkspaceContactVO toContactVO(AgentUser user) {
        WorkspaceContactVO vo = new WorkspaceContactVO();
        vo.setId(user.getId());
        vo.setUsername(user.getUsername());
        vo.setDisplayName(user.getDisplayName());
        vo.setEnglishName(user.getEnglishName());
        vo.setAvatar(user.getAvatar());
        vo.setStatus(user.getStatus());
        vo.setLastLoginAt(user.getLastLoginAt());
        vo.setRoles(roleSpi.listByUserId(user.getId()).stream().map(r -> r.getCode()).toList());
        return vo;
    }
}
