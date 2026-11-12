package com.buukle.agent.superagent.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.buukle.agent.common.context.AuthContext;
import com.buukle.agent.common.context.TenantContext;
import com.buukle.agent.common.error.CommonErrorCode;
import com.buukle.agent.common.exception.BizException;
import com.buukle.agent.common.util.TokenHasher;
import com.buukle.agent.superagent.domain.AgentLocalRuntime;
import com.buukle.agent.superagent.dtvo.dto.CreateLocalRuntimeDTO;
import com.buukle.agent.superagent.dtvo.dto.LocalRuntimeHeartbeatDTO;
import com.buukle.agent.superagent.dtvo.dto.UpdateLocalRuntimeDTO;
import com.buukle.agent.superagent.dtvo.enums.LocalRuntimeKind;
import com.buukle.agent.superagent.dtvo.enums.LocalRuntimeStatus;
import com.buukle.agent.superagent.dtvo.vo.IssuedLocalRuntimeTokenVO;
import com.buukle.agent.superagent.dtvo.vo.LocalRuntimeVO;
import com.buukle.agent.superagent.repository.AgentLocalRuntimeMapper;
import com.buukle.agent.superagent.repository.AgentLocalTaskMapper;
import com.buukle.agent.superagent.service.LocalRuntimeService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class LocalRuntimeServiceImpl implements LocalRuntimeService {

    private static final int ONLINE_TTL_SECONDS = 90;

    private final AgentLocalRuntimeMapper runtimeMapper;
    private final AgentLocalTaskMapper taskMapper;

    @Value("${buukle.agent.super-agent.local.task-timeout-seconds:600}")
    private long taskTimeoutSeconds;

    @Override
    @Transactional
    public IssuedLocalRuntimeTokenVO create(CreateLocalRuntimeDTO dto) {
        Long userId = currentUserId();
        String runtimeKind = normalizeKind(dto.getRuntimeKind());
        String deviceKey = dto.getDeviceKey() == null || dto.getDeviceKey().isBlank()
                ? UUID.randomUUID().toString() : dto.getDeviceKey().trim();

        AgentLocalRuntime existing = findExistingDevice(deviceKey, userId);
        if (existing != null) {
            // Reconnecting a device must not fail just because its runtime record
            // already exists: reuse it and issue a fresh token. The desktop then
            // rebinds the avatar to the same runtime instead of creating a duplicate.
            return reuse(existing, dto, runtimeKind);
        }

        String token = TokenHasher.generate();
        AgentLocalRuntime runtime = new AgentLocalRuntime();
        runtime.setOwnerUserId(userId);
        runtime.setName(dto.getName().trim());
        runtime.setRuntimeKind(runtimeKind);
        runtime.setDeviceKey(deviceKey);
        runtime.setTokenHash(TokenHasher.sha256(token));
        runtime.setTokenPrefix(TokenHasher.prefix(token));
        runtime.setOs(dto.getOs());
        runtime.setArch(dto.getArch());
        runtime.setAppVersion(dto.getAppVersion());
        runtime.setRuntimeVersion(dto.getRuntimeVersion());
        runtime.setStatus(LocalRuntimeStatus.ACTIVE);
        runtime.setTenantId(TenantContext.get());

        try {
            runtimeMapper.insert(runtime);
        } catch (DuplicateKeyException e) {
            // Concurrent create for the same device won the race; let the caller retry.
            throw new BizException(CommonErrorCode.PARAM_INVALID,
                    "this device is already registered");
        }

        return issued(runtime, token);
    }

    private AgentLocalRuntime findExistingDevice(String deviceKey, Long ownerUserId) {
        return runtimeMapper.selectOne(Wrappers.<AgentLocalRuntime>lambdaQuery()
                .eq(AgentLocalRuntime::getDeviceKey, deviceKey)
                .eq(AgentLocalRuntime::getOwnerUserId, ownerUserId)
                .last("LIMIT 1"));
    }

    private IssuedLocalRuntimeTokenVO reuse(AgentLocalRuntime existing,
                                            CreateLocalRuntimeDTO dto,
                                            String runtimeKind) {
        String token = TokenHasher.generate();
        existing.setName(dto.getName().trim());
        existing.setRuntimeKind(runtimeKind);
        existing.setTokenHash(TokenHasher.sha256(token));
        existing.setTokenPrefix(TokenHasher.prefix(token));
        existing.setOs(dto.getOs());
        existing.setArch(dto.getArch());
        existing.setAppVersion(dto.getAppVersion());
        existing.setRuntimeVersion(dto.getRuntimeVersion());
        existing.setStatus(LocalRuntimeStatus.ACTIVE);
        runtimeMapper.updateById(existing);
        return issued(existing, token);
    }

    private IssuedLocalRuntimeTokenVO issued(AgentLocalRuntime runtime, String token) {
        IssuedLocalRuntimeTokenVO vo = new IssuedLocalRuntimeTokenVO();
        vo.setId(runtime.getId());
        vo.setName(runtime.getName());
        vo.setRuntimeKind(runtime.getRuntimeKind());
        vo.setToken(token);
        vo.setTokenPrefix(runtime.getTokenPrefix());
        return vo;
    }

    @Override
    public List<LocalRuntimeVO> list() {
        Long userId = currentUserId();
        var query = Wrappers.<AgentLocalRuntime>lambdaQuery()
                .orderByDesc(AgentLocalRuntime::getId);
        if (!AuthContext.isSuperAdmin()) {
            query.eq(AgentLocalRuntime::getOwnerUserId, userId);
        }
        return runtimeMapper.selectList(query).stream().map(this::toVO).toList();
    }

    @Override
    @Transactional
    public LocalRuntimeVO update(Long id, UpdateLocalRuntimeDTO dto) {
        AgentLocalRuntime runtime = requireOwned(id);
        if (dto.getName() != null && !dto.getName().isBlank()) {
            runtime.setName(dto.getName().trim());
        }
        if (dto.getStatus() != null && !dto.getStatus().isBlank()) {
            String status = dto.getStatus().trim().toUpperCase(Locale.ROOT);
            if (!LocalRuntimeStatus.ACTIVE.equals(status)
                    && !LocalRuntimeStatus.DISABLED.equals(status)) {
                throw new BizException(CommonErrorCode.PARAM_INVALID,
                        "status must be ACTIVE or DISABLED");
            }
            runtime.setStatus(status);
        }
        runtimeMapper.updateById(runtime);
        return toVO(runtime);
    }

    @Override
    @Transactional
    public IssuedLocalRuntimeTokenVO rotateToken(Long id) {
        AgentLocalRuntime runtime = requireOwned(id);
        String token = TokenHasher.generate();
        runtime.setTokenHash(TokenHasher.sha256(token));
        runtime.setTokenPrefix(TokenHasher.prefix(token));
        runtimeMapper.updateById(runtime);

        IssuedLocalRuntimeTokenVO vo = new IssuedLocalRuntimeTokenVO();
        vo.setId(runtime.getId());
        vo.setName(runtime.getName());
        vo.setRuntimeKind(runtime.getRuntimeKind());
        vo.setToken(token);
        vo.setTokenPrefix(runtime.getTokenPrefix());
        return vo;
    }

    @Override
    @Transactional
    public void delete(Long id) {
        requireOwned(id);
        runtimeMapper.deleteById(id);
    }

    @Override
    public LocalRuntimeVO me(Long runtimeId) {
        AgentLocalRuntime runtime = runtimeMapper.selectById(runtimeId);
        if (runtime == null || !LocalRuntimeStatus.ACTIVE.equals(runtime.getStatus())) {
            throw new BizException(CommonErrorCode.FORBIDDEN);
        }
        return toVO(runtime);
    }

    @Override
    @Transactional
    public LocalRuntimeVO heartbeat(Long runtimeId, LocalRuntimeHeartbeatDTO dto) {
        AgentLocalRuntime runtime = runtimeMapper.selectById(runtimeId);
        if (runtime == null || !LocalRuntimeStatus.ACTIVE.equals(runtime.getStatus())) {
            throw new BizException(CommonErrorCode.FORBIDDEN);
        }
        runtime.setLastSeenAt(LocalDateTime.now());
        runtime.setAppVersion(dto.getAppVersion());
        runtime.setOs(dto.getOs());
        runtime.setArch(dto.getArch());
        runtimeMapper.updateById(runtime);

        if (dto.getRunningTaskIds() != null && !dto.getRunningTaskIds().isEmpty()) {
            LocalDateTime leaseUntil = LocalDateTime.now().plusSeconds(Math.max(30, taskTimeoutSeconds));
            for (Long taskId : dto.getRunningTaskIds()) {
                if (taskId != null) {
                    taskMapper.renewLease(taskId, runtimeId, leaseUntil);
                }
            }
        }
        return toVO(runtime);
    }

    private AgentLocalRuntime requireOwned(Long id) {
        AgentLocalRuntime runtime = runtimeMapper.selectById(id);
        if (runtime == null) {
            throw new BizException(CommonErrorCode.RESOURCE_NOT_FOUND, "local runtime not found");
        }
        if (!AuthContext.isSuperAdmin()
                && !currentUserId().equals(runtime.getOwnerUserId())) {
            throw new BizException(CommonErrorCode.FORBIDDEN);
        }
        return runtime;
    }

    private LocalRuntimeVO toVO(AgentLocalRuntime runtime) {
        LocalRuntimeVO vo = new LocalRuntimeVO();
        vo.setId(runtime.getId());
        vo.setName(runtime.getName());
        vo.setRuntimeKind(runtime.getRuntimeKind());
        vo.setStatus(runtime.getStatus());
        vo.setOnline(runtime.getLastSeenAt() != null
                && runtime.getLastSeenAt().isAfter(
                        LocalDateTime.now().minusSeconds(ONLINE_TTL_SECONDS)));
        vo.setAppVersion(runtime.getAppVersion());
        vo.setRuntimeVersion(runtime.getRuntimeVersion());
        vo.setOs(runtime.getOs());
        vo.setArch(runtime.getArch());
        vo.setTokenPrefix(runtime.getTokenPrefix());
        vo.setLastSeenAt(runtime.getLastSeenAt());
        vo.setCreatedAt(runtime.getCreatedAt());
        return vo;
    }

    private static String normalizeKind(String value) {
        String kind = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        if (!LocalRuntimeKind.OPENCODE.equals(kind) && !LocalRuntimeKind.CODEX.equals(kind)) {
            throw new BizException(CommonErrorCode.PARAM_INVALID,
                    "runtimeKind must be OPENCODE or CODEX");
        }
        return kind;
    }

    private static Long currentUserId() {
        Long userId = AuthContext.getUserId();
        if (userId == null) {
            throw new BizException(CommonErrorCode.UNAUTHORIZED);
        }
        return userId;
    }
}
