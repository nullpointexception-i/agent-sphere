package com.buukle.agent.capability.skill.service.converter;

import com.buukle.agent.capability.skill.domain.CapabilitySkill;
import com.buukle.agent.capability.skill.dtvo.dto.CreateSkillDTO;
import com.buukle.agent.capability.skill.dtvo.enums.SkillCapabilityEnum;
import com.buukle.agent.capability.skill.dtvo.vo.SkillVO;
import org.springframework.stereotype.Component;

import java.time.format.DateTimeFormatter;

@Component
public class CapabilitySkillConverter {
    private static final DateTimeFormatter DTF = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    public SkillVO toVO(CapabilitySkill skill) {
        if (skill == null) return null;
        SkillVO vo = new SkillVO();
        vo.setId(skill.getId());
        vo.setName(skill.getName());
        vo.setDescription(skill.getDescription());
        vo.setDefinition(skill.getDefinition());
        vo.setStatus(skill.getStatus());
        vo.setCreatedAt(skill.getCreatedAt() != null ? skill.getCreatedAt().format(DTF) : null);
        vo.setCreatedBy(skill.getCreatedBy());
        vo.setUpdatedBy(skill.getUpdatedBy());
        vo.setUpdatedAt(skill.getUpdatedAt() != null ? skill.getUpdatedAt().format(DTF) : null);
        return vo;
    }

    public CapabilitySkill toDO(CreateSkillDTO dto) {
        CapabilitySkill skill = new CapabilitySkill();
        skill.setName(dto.getName());
        skill.setDescription(dto.getDescription());
        skill.setDefinition(dto.getDefinition());
        skill.setStatus(SkillCapabilityEnum.STATUS_ENABLED);
        return skill;
    }
}
