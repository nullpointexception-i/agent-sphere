package com.buukle.agent.infrastructure.config;

import com.buukle.agent.instance.domain.AgentUser;
import com.buukle.agent.instance.repository.UserMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class PasswordUpgradeRunner implements ApplicationRunner {

    private final UserMapper userMapper;
    private final PasswordEncoder passwordEncoder;

    @Override
    public void run(ApplicationArguments args) {
        List<AgentUser> users = userMapper.selectList(null);
        for (AgentUser user : users) {
            String pwd = user.getPassword();
            if (pwd != null && pwd.length() == 64 && !pwd.startsWith("$2")) {
                // Looks like a SHA-256 hex string (64 chars, not bcrypt)
                log.warn("User '{}' still has legacy SHA-256 password. Password will be upgraded on next login.", user.getUsername());
            }
        }
    }
}
