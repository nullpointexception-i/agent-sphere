package com.buukle.agent.instance.service.impl;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.buukle.agent.common.exception.BizException;
import com.buukle.agent.common.util.AvatarGenerator;
import com.buukle.agent.instance.domain.AgentInstance;
import com.buukle.agent.instance.dtvo.dto.CreateInstanceDTO;
import com.buukle.agent.instance.dtvo.vo.InstanceVO;
import com.buukle.agent.instance.exception.InstanceErrorCode;
import com.buukle.agent.instance.repository.InstanceMapper;
import com.buukle.agent.instance.service.InstanceService;
import com.buukle.agent.instance.service.converter.InstanceConverter;
import com.buukle.agent.model.spi.RouteSpi;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class InstanceServiceImpl extends ServiceImpl<InstanceMapper, AgentInstance> implements InstanceService {
    private final InstanceConverter instanceConverter;
    private final RouteSpi routeSpi;

    @Override
    public InstanceVO createInstance(CreateInstanceDTO dto) {
        if (dto.getImage() == null || dto.getImage().isBlank()) {
            dto.setImage(AvatarGenerator.generateBase64());
        } else {
            byte[] decoded = java.util.Base64.getDecoder().decode(dto.getImage().contains(",") ? dto.getImage().split(",")[1] : dto.getImage());
            if (decoded.length > 2 * 1024 * 1024) {
                throw new BizException(com.buukle.agent.common.error.CommonErrorCode.PARAM_INVALID, "Image too large, max 2MB");
            }
        }
        AgentInstance instance = instanceConverter.toDO(dto);
        save(instance);
        return instanceConverter.toVO(instance);
    }

    @Override
    public InstanceVO updateInstance(Long id, CreateInstanceDTO dto) {
        requireOwnership(id);
        if (dto.getImage() != null && !dto.getImage().isBlank()) {
            byte[] decoded = java.util.Base64.getDecoder().decode(dto.getImage().contains(",") ? dto.getImage().split(",")[1] : dto.getImage());
            if (decoded.length > 2 * 1024 * 1024) {
                throw new BizException(com.buukle.agent.common.error.CommonErrorCode.PARAM_INVALID, "Image too large, max 2MB");
            }
        }
        AgentInstance instance = instanceConverter.toDO(dto);
        instance.setId(id);
        updateById(instance);
        AgentInstance saved = getById(id);
        return instanceConverter.toVO(saved);
    }

    @Override
    public void deleteInstance(Long id) {
        requireOwnership(id);
        removeById(id);
    }

    @Override
    public void batchDeleteInstances(List<Long> ids) {
        if (ids == null || ids.isEmpty()) return;
        for (Long id : ids) {
            requireOwnership(id);
        }
        removeByIds(ids);
    }

    private void requireOwnership(Long id) {
        if (com.buukle.agent.common.context.AuthContext.isSuperAdmin()) {
            return;
        }
        AgentInstance existing = getById(id);
        if (existing == null) {
            throw new BizException(InstanceErrorCode.INSTANCE_NOT_FOUND);
        }
        String owner = com.buukle.agent.common.context.AuthContext.getUsername();
        if (!java.util.Objects.equals(owner, existing.getCreatedBy())) {
            throw new BizException(com.buukle.agent.common.error.CommonErrorCode.FORBIDDEN, "无权操作他人实例");
        }
    }

    @Override
    public InstanceVO getInstance(Long id) {
        AgentInstance instance = getById(id);
        if (instance == null) throw new BizException(InstanceErrorCode.INSTANCE_NOT_FOUND);
        return instanceConverter.toVO(instance);
    }

    @Override
    public List<InstanceVO> listInstances(String keyword, LocalDateTime startTime, LocalDateTime endTime) {
        log.info("listInstances called: keyword='{}', startTime={}, endTime={}", keyword, startTime, endTime);
        List<AgentInstance> instances = lambdaQuery()
                .like(keyword != null && !keyword.isBlank(), AgentInstance::getName, keyword)
                .ge(startTime != null, AgentInstance::getCreatedAt, startTime)
                .le(endTime != null, AgentInstance::getCreatedAt, endTime)
                .orderByDesc(AgentInstance::getCreatedAt)
                .list();
        log.info("listInstances result: {} rows", instances.size());
        return instances.stream().map(instanceConverter::toVO).toList();
    }

    @Override
    public IPage<InstanceVO> pageInstances(int page, int size, String keyword, LocalDateTime startTime, LocalDateTime endTime) {
        Page<AgentInstance> p = lambdaQuery()
                .like(keyword != null && !keyword.isBlank(), AgentInstance::getName, keyword)
                .ge(startTime != null, AgentInstance::getCreatedAt, startTime)
                .le(endTime != null, AgentInstance::getCreatedAt, endTime)
                .orderByDesc(AgentInstance::getCreatedAt)
                .page(new Page<>(page, size));
        return p.convert(instanceConverter::toVO);
    }

    @Override
    public InstanceVO setModelRoute(Long id, Long modelRouteId) {
        AgentInstance instance = getById(id);
        if (instance == null) throw new BizException(InstanceErrorCode.INSTANCE_NOT_FOUND);
        if (modelRouteId != null) {
            var route = routeSpi.getRoute(modelRouteId);
            if (route.getApiKeyConfigured() != null && !route.getApiKeyConfigured()) {
                throw new BizException(InstanceErrorCode.ROUTE_NO_API_KEY);
            }
        }
        instance.setModelRouteId(modelRouteId);
        updateById(instance);
        return instanceConverter.toVO(instance);
    }

    @Override
    public List<InstanceVO> listLatestNInstances(int n) {
        int limit = Math.min(Math.max(n, 1), 32);
        List<AgentInstance> instances = lambdaQuery()
                .orderByDesc(AgentInstance::getCreatedAt)
                .last("LIMIT " + limit)
                .list();
        return instances.stream().map(instanceConverter::toVO).toList();
    }
}
