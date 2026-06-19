package com.buukle.agent.instance.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.buukle.agent.common.config.AgentRuntimeProperties;
import com.buukle.agent.instance.domain.AgentInstanceCapability;
import com.buukle.agent.instance.dtvo.dto.BatchCreateInstanceCapabilityDTO;
import com.buukle.agent.instance.dtvo.dto.CreateInstanceCapabilityDTO;
import com.buukle.agent.instance.dtvo.vo.CapabilityVO;
import com.buukle.agent.instance.repository.InstanceCapabilityMapper;
import com.buukle.agent.instance.service.InstanceCapabilityService;
import com.buukle.agent.instance.dtvo.enums.InstanceCapabilityEnum;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Primary
@Service
public class InstanceCapabilityServiceImpl extends ServiceImpl<InstanceCapabilityMapper, AgentInstanceCapability> implements InstanceCapabilityService {
    private final RedissonClient redissonClient;
    private final Duration lockWaitTime;
    private final Duration lockLeaseTime;

    public InstanceCapabilityServiceImpl(RedissonClient redissonClient, AgentRuntimeProperties properties) {
        this.redissonClient = redissonClient;
        this.lockWaitTime = properties.getLock().getCapability().getWaitTime();
        this.lockLeaseTime = properties.getLock().getCapability().getLeaseTime();
    }

    @Override
    public List<CapabilityVO> getCapabilitiesByInstance(Long instanceId) {
        List<AgentInstanceCapability> list = lambdaQuery().eq(AgentInstanceCapability::getInstanceId, instanceId).list();
        List<CapabilityVO> result = new ArrayList<>();
        for (AgentInstanceCapability cap : list) {
            CapabilityVO vo = new CapabilityVO();
            vo.setId(cap.getId());
            vo.setCapabilityType(cap.getCapabilityType());
            vo.setCapabilityId(cap.getCapabilityId());
            vo.setStatus(cap.getStatus());
            result.add(vo);
        }
        return result;
    }

    @Override
    public CapabilityVO createCapability(CreateInstanceCapabilityDTO dto) {
        AgentInstanceCapability cap = new AgentInstanceCapability();
        cap.setInstanceId(dto.getInstanceId());
        cap.setCapabilityType(dto.getCapabilityType());
        cap.setCapabilityId(dto.getCapabilityId());
        cap.setStatus(dto.getStatus() != null ? dto.getStatus() : InstanceCapabilityEnum.STATUS_ENABLED);
        save(cap);
        CapabilityVO vo = new CapabilityVO();
        vo.setId(cap.getId());
        vo.setCapabilityType(cap.getCapabilityType());
        vo.setCapabilityId(cap.getCapabilityId());
        vo.setStatus(cap.getStatus());
        return vo;
    }

    @Override
    public void deleteCapability(Long id) {
        removeById(id);
    }

    @Override
    public List<CapabilityVO> batchCreateCapabilities(BatchCreateInstanceCapabilityDTO dto) {
        List<BatchCreateInstanceCapabilityDTO.CapabilityItem> items = dto.getCapabilities();
        if (items.isEmpty()) return List.of();
        Long instanceId = items.get(0).getInstanceId();
        String lockKey = "lock:instance:capabilities:" + instanceId;
        RLock lock = redissonClient.getLock(lockKey);
        try {
            if (!lock.tryLock(lockWaitTime.getSeconds(), lockLeaseTime.getSeconds(), TimeUnit.SECONDS)) {
                throw new RuntimeException("Failed to acquire lock for instance capability update");
            }
            try {
                List<CapabilityVO> result = new ArrayList<>();
                for (BatchCreateInstanceCapabilityDTO.CapabilityItem item : items) {
                    baseMapper.hardDeleteByUniqueKey(item.getInstanceId(), item.getCapabilityType(), item.getCapabilityId());
                    AgentInstanceCapability cap = new AgentInstanceCapability();
                    cap.setInstanceId(item.getInstanceId());
                    cap.setCapabilityType(item.getCapabilityType());
                    cap.setCapabilityId(item.getCapabilityId());
                    cap.setStatus(item.getStatus() != null ? item.getStatus() : InstanceCapabilityEnum.STATUS_ENABLED);
                    save(cap);
                    CapabilityVO vo = new CapabilityVO();
                    vo.setId(cap.getId());
                    vo.setCapabilityType(cap.getCapabilityType());
                    vo.setCapabilityId(cap.getCapabilityId());
                    vo.setStatus(cap.getStatus());
                    result.add(vo);
                }
                return result;
            } finally {
                if (lock.isHeldByCurrentThread()) {
                    lock.unlock();
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Lock acquisition interrupted", e);
        }
    }
}
