package com.buukle.agent.instance.spi;

import com.buukle.agent.instance.dtvo.dto.LoginDTO;
import com.buukle.agent.instance.dtvo.dto.RegisterDTO;
import com.buukle.agent.instance.dtvo.vo.UserVO;

import java.util.List;

public interface UserSpi {
    UserVO login(LoginDTO dto);

    UserVO getCurrentUser();

    void logout();

    void updateProfile(Long userId, String displayName, String englishName, String avatar);

    void updatePassword(Long userId, String oldPassword, String newPassword);

    boolean checkUsername(String username);

    UserVO register(RegisterDTO dto);

    UserVO loginByUserId(Long userId);

    com.baomidou.mybatisplus.extension.plugins.pagination.Page<UserVO> listPage(int page, int size);
}
