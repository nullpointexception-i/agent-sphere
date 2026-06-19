package com.buukle.agent.instance.spi;

import com.buukle.agent.instance.dtvo.dto.LoginDTO;
import com.buukle.agent.instance.dtvo.dto.RegisterDTO;
import com.buukle.agent.instance.dtvo.vo.UserVO;

public interface UserSpi {
    UserVO login(LoginDTO dto);
    UserVO getCurrentUser();
    void logout();
    void updateProfile(Long userId, String displayName, String englishName, String avatar);
    void updatePassword(Long userId, String oldPassword, String newPassword);
    boolean checkUsername(String username);
    UserVO register(RegisterDTO dto);
}
