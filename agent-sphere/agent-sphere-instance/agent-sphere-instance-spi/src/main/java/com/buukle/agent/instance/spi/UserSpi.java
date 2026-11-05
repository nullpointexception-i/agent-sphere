package com.buukle.agent.instance.spi;

import com.buukle.agent.instance.dtvo.dto.LoginDTO;
import com.buukle.agent.instance.dtvo.dto.RegisterDTO;
import com.buukle.agent.instance.dtvo.vo.UserIdentityVO;
import com.buukle.agent.instance.dtvo.vo.UserSessionVO;
import com.buukle.agent.instance.dtvo.vo.UserVO;
import com.buukle.agent.instance.dtvo.vo.WorkspaceContactVO;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;

import java.util.Collection;
import java.util.List;
import java.util.Map;

public interface UserSpi {
    UserVO login(LoginDTO dto);

    UserVO getCurrentUser();

    void logout();

    /** 退出所有设备：吊销当前用户的全部有效会话。 */
    void logoutAll();

    /** 当前用户的有效登录会话（设备管理）。 */
    List<UserSessionVO> listSessions();

    /** 吊销当前用户的一条指定会话。 */
    void revokeSession(Long sessionId);

    void updateProfile(Long userId, String displayName, String englishName, String avatar);

    void updatePassword(Long userId, String oldPassword, String newPassword);

    boolean checkUsername(String username);

    UserVO register(RegisterDTO dto);

    UserVO register(RegisterDTO dto, Long defaultRoleId);

    UserVO loginByUserId(Long userId);

    UserVO getByUserId(Long userId);

    com.baomidou.mybatisplus.extension.plugins.pagination.Page<UserVO> listPage(int page, int size);

    /** Safe contact fields for the C-end workspace; never exposes token/email/permissions. */
    Page<WorkspaceContactVO> pageContacts(int page, int size, String keyword);

    /** Lightweight id -> current identity lookup for cross-domain display. */
    Map<Long, UserIdentityVO> identityMap(Collection<Long> userIds);
}
