package com.buukle.agent.instance.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.buukle.agent.admin.spi.PermissionSpi;
import com.buukle.agent.admin.spi.RoleSpi;
import com.buukle.agent.common.context.AuthContext;
import com.buukle.agent.common.error.CommonErrorCode;
import com.buukle.agent.common.exception.BizException;
import com.buukle.agent.common.util.AvatarGenerator;
import com.buukle.agent.instance.domain.AgentUser;
import com.buukle.agent.instance.dtvo.dto.LoginDTO;
import com.buukle.agent.instance.dtvo.dto.RegisterDTO;
import com.buukle.agent.instance.dtvo.enums.UserEnum;
import com.buukle.agent.instance.dtvo.vo.UserVO;
import com.buukle.agent.instance.exception.InstanceErrorCode;
import com.buukle.agent.instance.repository.UserMapper;
import com.buukle.agent.instance.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

@Service
@RequiredArgsConstructor
public class UserServiceImpl extends ServiceImpl<UserMapper, AgentUser> implements UserService {

    private final PasswordEncoder passwordEncoder;
    private final PermissionSpi permissionSpi;
    private final RoleSpi roleSpi;

    public static String sha256(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(input.getBytes());
            return HexFormat.of().formatHex(digest);
        } catch (Exception e) {
            throw new BizException(CommonErrorCode.INTERNAL_ERROR, "SHA-256 error", e);
        }
    }

    private static String generateToken() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private UserVO toVO(AgentUser user) {
        UserVO vo = new UserVO();
        vo.setId(user.getId());
        vo.setUsername(user.getUsername());
        vo.setDisplayName(user.getDisplayName());
        vo.setEnglishName(user.getEnglishName());
        vo.setEmail(user.getEmail());
        vo.setAvatar(user.getAvatar());
        vo.setToken(user.getToken());
        vo.setStatus(user.getStatus());
        vo.setSuperAdmin(user.getSuperAdmin());
        vo.setRoles(roleSpi.listByUserId(user.getId()).stream().map(r -> r.getCode()).toList());
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

        String token = generateToken();
        user.setToken(token);
        updateById(user);

        AuthContext.setToken(token);
        AuthContext.setUserId(user.getId());
        AuthContext.setUsername(user.getUsername());
        AuthContext.setDisplayName(user.getDisplayName());
        AuthContext.setSuperAdmin(UserEnum.IS_SUPER_ADMIN.equals(user.getSuperAdmin()));

        return toVO(user);
    }

    @Override
    public UserVO getCurrentUser() {
        String token = AuthContext.getToken();
        if (token == null) return null;
        AgentUser user = lambdaQuery().eq(AgentUser::getToken, token).one();
        return user != null ? toVO(user) : null;
    }

    @Override
    public void logout() {
        String token = AuthContext.getToken();
        if (token != null) {
            lambdaUpdate().eq(AgentUser::getToken, token).set(AgentUser::getToken, null).update();
        }
        AuthContext.clear();
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
    }

    @Override
    public boolean checkUsername(String username) {
        return lambdaQuery().eq(AgentUser::getUsername, username).count() == 0;
    }

    @Override
    public UserVO register(RegisterDTO dto) {
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
        // Auto-login
        String token = generateToken();
        user.setToken(token);
        updateById(user);
        AuthContext.setToken(token);
        AuthContext.setUserId(user.getId());
        AuthContext.setUsername(user.getUsername());
        AuthContext.setDisplayName(user.getDisplayName());
        AuthContext.setSuperAdmin(UserEnum.IS_SUPER_ADMIN.equals(user.getSuperAdmin()));
        return toVO(user);
    }

    @Override
    public com.baomidou.mybatisplus.extension.plugins.pagination.Page<UserVO> listPage(int page, int size) {
        var mpPage = lambdaQuery().page(new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>(page, size));
        var voPage = new com.baomidou.mybatisplus.extension.plugins.pagination.Page<UserVO>(mpPage.getCurrent(), mpPage.getSize(), mpPage.getTotal());
        voPage.setRecords(mpPage.getRecords().stream().map(this::toVO).toList());
        return voPage;
    }
}
