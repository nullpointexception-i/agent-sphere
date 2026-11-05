package com.buukle.agent.instance.service.impl;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.buukle.agent.admin.spi.PermissionSpi;
import com.buukle.agent.admin.spi.RoleSpi;
import com.buukle.agent.common.context.AuthContext;
import com.buukle.agent.common.context.SessionSurfaceHolder;
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
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class UserServiceImpl extends ServiceImpl<UserMapper, AgentUser> implements UserService {

    private final PasswordEncoder passwordEncoder;
    private final PermissionSpi permissionSpi;
    private final RoleSpi roleSpi;
    private final ApplicationEventPublisher eventPublisher;
    private final UserTokenSpi userTokenSpi;

    public static String sha256(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(input.getBytes());
            return HexFormat.of().formatHex(digest);
        } catch (Exception e) {
            throw new BizException(CommonErrorCode.INTERNAL_ERROR, "SHA-256 error", e);
        }
    }

    /** 当前请求来源端（CONSOLE / WIDGET / INTERNAL），写入会话行用于设备管理。 */
    private static String currentTokenSource() {
        return SessionSurfaceHolder.current().name();
    }

    private static final String DEFAULT_USER_ROLE_CODE = "USER";

    /** 解析新用户初始角色：指定 defaultRoleId 则替换默认 USER 角色（SSO 开通场景）。 */
    public static List<Long> resolveInitialRoleIds(RoleSpi roleSpi, Long defaultRoleId) {
        if (defaultRoleId != null) {
            return List.of(defaultRoleId);
        }
        return roleSpi.listAll().stream()
                .filter(r -> DEFAULT_USER_ROLE_CODE.equals(r.getCode()))
                .findFirst()
                .map(r -> List.of(r.getId()))
                .orElseGet(List::of);
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
        var roles = roleSpi.listByUserId(user.getId());
        vo.setRoles(roles.stream().map(r -> r.getCode()).toList());
        vo.setDemo(roles.stream().anyMatch(r -> "DEMO".equals(r.getCode())));
        boolean isSuperAdmin = UserEnum.IS_SUPER_ADMIN.equals(user.getSuperAdmin());
        vo.setPermissions(isSuperAdmin ? permissionSpi.listAllCodes() : permissionSpi.listCodesByUserId(user.getId()));
        return vo;
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
        AuthContext.clear();
    }

    @Override
    public void logoutAll() {
        Long userId = AuthContext.getUserId();
        if (userId != null) {
            userTokenSpi.revokeAll(userId);
        }
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
        // 改密后其它设备必须重新登录；当前设备保留，避免用户改完密码自己被踢出去
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
        // 角色：指定 defaultRoleId 则替换默认 USER 角色（SSO 开通场景）
        List<Long> initialRoleIds = resolveInitialRoleIds(roleSpi, defaultRoleId);
        if (!initialRoleIds.isEmpty()) {
            roleSpi.assignRoles(user.getId(), initialRoleIds);
        }
        // 自助注册（非 SSO 开通）：发布事件触发资源初始化模板（NativeUserResourceProvisioner）
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
        UserVO vo = toVO(user);
        vo.setToken(issued.getToken());
        return vo;
    }

    @Override
    public com.baomidou.mybatisplus.extension.plugins.pagination.Page<UserVO> listPage(int page, int size) {
        var mpPage = lambdaQuery().page(new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>(page, size));
        var voPage = new com.baomidou.mybatisplus.extension.plugins.pagination.Page<UserVO>(mpPage.getCurrent(), mpPage.getSize(), mpPage.getTotal());
        voPage.setRecords(mpPage.getRecords().stream().map(this::toVO).toList());
        return voPage;
    }

    @Override
    public Page<WorkspaceContactVO> pageContacts(int page, int size, String keyword) {
        Long tenantId = com.buukle.agent.common.context.TenantContext.get();
        var mpPage = lambdaQuery()
                .eq(AgentUser::getStatus, UserEnum.STATUS_ACTIVE)
                .eq(tenantId != null, AgentUser::getTenantId, tenantId)
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
