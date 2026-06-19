package com.buukle.agent.instance.controller;

import com.buukle.agent.common.util.BaseController;
import com.buukle.agent.common.exception.BizException;
import com.buukle.agent.common.error.CommonErrorCode;
import com.buukle.agent.instance.dtvo.dto.LoginDTO;
import com.buukle.agent.instance.dtvo.dto.RegisterDTO;
import com.buukle.agent.instance.dtvo.dto.UpdatePasswordDTO;
import com.buukle.agent.instance.dtvo.dto.UpdateProfileDTO;
import com.buukle.agent.instance.dtvo.vo.UserVO;
import com.buukle.agent.instance.service.UserService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController extends BaseController {
    private final UserService userService;

    private Long getCurrentUserId() {
        UserVO user = userService.getCurrentUser();
        if (user == null) throw new BizException(CommonErrorCode.UNAUTHORIZED);
        return user.getId();
    }

    @PostMapping("/login")
    public ResponseEntity<?> login(@Valid @RequestBody LoginDTO dto) {
        return ok(userService.login(dto));
    }

    @PostMapping("/register")
    public ResponseEntity<?> register(@Valid @RequestBody RegisterDTO dto) {
        return ok(userService.register(dto));
    }

    @GetMapping("/check-username")
    public ResponseEntity<?> checkUsername(@RequestParam String username) {
        return ok(userService.checkUsername(username));
    }

    @GetMapping("/me")
    public ResponseEntity<?> me() {
        UserVO user = userService.getCurrentUser();
        return ok(user);
    }

    @PostMapping("/logout")
    public ResponseEntity<?> logout() {
        userService.logout();
        return ok();
    }

    @PutMapping("/profile")
    public ResponseEntity<?> updateProfile(@Valid @RequestBody UpdateProfileDTO dto) {
        userService.updateProfile(getCurrentUserId(), dto.getDisplayName(), dto.getEnglishName(), dto.getAvatar());
        return ok();
    }

    @PutMapping("/password")
    public ResponseEntity<?> updatePassword(@Valid @RequestBody UpdatePasswordDTO dto) {
        userService.updatePassword(getCurrentUserId(), dto.getOldPassword(), dto.getNewPassword());
        return ok();
    }
}
