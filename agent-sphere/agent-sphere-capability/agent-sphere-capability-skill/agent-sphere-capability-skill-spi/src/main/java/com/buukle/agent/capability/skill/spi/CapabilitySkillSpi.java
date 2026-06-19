package com.buukle.agent.capability.skill.spi;

import com.buukle.agent.capability.skill.dtvo.dto.CreateSkillDTO;
import com.buukle.agent.capability.skill.dtvo.vo.SkillVO;
import com.baomidou.mybatisplus.core.metadata.IPage;
import java.time.LocalDateTime;
import java.util.List;

public interface CapabilitySkillSpi {
    SkillVO createSkill(CreateSkillDTO dto);
    SkillVO getSkill(Long id);
    List<SkillVO> listSkills(String keyword, LocalDateTime startTime, LocalDateTime endTime);
    IPage<SkillVO> pageSkills(int page, int size, String keyword, LocalDateTime startTime, LocalDateTime endTime);
    SkillVO updateSkill(Long id, CreateSkillDTO dto);
    void deleteSkill(Long id);
    void batchDeleteSkill(java.util.List<Long> ids);
    List<SkillVO> listSkillsByIds(List<Long> ids);
}
