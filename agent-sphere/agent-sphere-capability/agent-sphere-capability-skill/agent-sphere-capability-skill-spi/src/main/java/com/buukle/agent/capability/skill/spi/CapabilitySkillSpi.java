package com.buukle.agent.capability.skill.spi;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.buukle.agent.capability.skill.dtvo.dto.CreateSkillDTO;
import com.buukle.agent.capability.skill.dtvo.vo.SkillVO;

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

    /** 单个启用/禁用。 */
    SkillVO updateStatus(Long id, String status);

    /** 批量启用/禁用。 */
    void batchUpdateStatus(java.util.List<Long> ids, String status);

    List<SkillVO> listSkillsByIds(List<Long> ids);
}
