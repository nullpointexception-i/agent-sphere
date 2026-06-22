package com.buukle.agent.capability.skill.service.impl;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.buukle.agent.capability.skill.domain.CapabilitySkill;
import com.buukle.agent.capability.skill.dtvo.dto.CreateSkillDTO;
import com.buukle.agent.capability.skill.dtvo.vo.SkillVO;
import com.buukle.agent.capability.skill.exception.CapabilitySkillErrorCode;
import com.buukle.agent.capability.skill.repository.SkillMapper;
import com.buukle.agent.capability.skill.service.CapabilitySkillService;
import com.buukle.agent.capability.skill.service.converter.CapabilitySkillConverter;
import com.buukle.agent.common.exception.BizException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
@Primary
public class CapabilitySkillServiceImpl extends ServiceImpl<SkillMapper, CapabilitySkill> implements CapabilitySkillService {
    private final CapabilitySkillConverter capabilitySkillConverter;

    @Override
    public SkillVO createSkill(CreateSkillDTO dto) {
        CapabilitySkill skill = capabilitySkillConverter.toDO(dto);
        save(skill);
        return capabilitySkillConverter.toVO(skill);
    }

    @Override
    public SkillVO getSkill(Long id) {
        CapabilitySkill skill = getById(id);
        if (skill == null) throw new BizException(CapabilitySkillErrorCode.SKILL_NOT_FOUND);
        return capabilitySkillConverter.toVO(skill);
    }

    @Override
    public SkillVO updateSkill(Long id, CreateSkillDTO dto) {
        CapabilitySkill skill = capabilitySkillConverter.toDO(dto);
        skill.setId(id);
        updateById(skill);
        return capabilitySkillConverter.toVO(skill);
    }

    @Override
    public void deleteSkill(Long id) {
        removeById(id);
    }

    @Override
    public void batchDeleteSkill(java.util.List<Long> ids) {
        removeByIds(ids);
    }

    @Override
    public List<SkillVO> listSkills(String keyword, LocalDateTime startTime, LocalDateTime endTime) {
        log.warn("listSkills called: keyword='{}', startTime={}, endTime={}", keyword, startTime, endTime);
        List<CapabilitySkill> list = lambdaQuery()
                .like(keyword != null && !keyword.isBlank(), CapabilitySkill::getName, keyword)
                .ge(startTime != null, CapabilitySkill::getCreatedAt, startTime)
                .le(endTime != null, CapabilitySkill::getCreatedAt, endTime)
                .orderByDesc(CapabilitySkill::getCreatedAt)
                .list();
        log.warn("listSkills result: {} rows", list.size());
        return list.stream().map(capabilitySkillConverter::toVO).toList();
    }

    @Override
    public IPage<SkillVO> pageSkills(int page, int size, String keyword, LocalDateTime startTime, LocalDateTime endTime) {
        Page<CapabilitySkill> p = lambdaQuery()
                .like(keyword != null && !keyword.isBlank(), CapabilitySkill::getName, keyword)
                .ge(startTime != null, CapabilitySkill::getCreatedAt, startTime)
                .le(endTime != null, CapabilitySkill::getCreatedAt, endTime)
                .orderByDesc(CapabilitySkill::getCreatedAt)
                .page(new Page<>(page, size));
        return p.convert(capabilitySkillConverter::toVO);
    }

    @Override
    public List<SkillVO> listSkillsByIds(List<Long> ids) {
        if (ids == null || ids.isEmpty()) return List.of();
        return lambdaQuery().in(CapabilitySkill::getId, ids).list().stream().map(capabilitySkillConverter::toVO).toList();
    }
}
