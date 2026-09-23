package com.buukle.agent.capability.skill.spi;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.buukle.agent.capability.skill.dtvo.dto.CreateSkillDTO;
import com.buukle.agent.capability.skill.dtvo.vo.SkillVO;

import java.time.LocalDateTime;
import java.util.List;

public interface CapabilitySkillSpi {
    SkillVO createSkill(CreateSkillDTO dto);

    /** 运行线程内创建技能时显式指定创建人（避免审计回落为 system）。 */
    SkillVO createSkill(CreateSkillDTO dto, String createdBy);

    SkillVO getSkill(Long id);

    List<SkillVO> listSkills(String keyword, LocalDateTime startTime, LocalDateTime endTime);

    IPage<SkillVO> pageSkills(int page, int size, String keyword, LocalDateTime startTime, LocalDateTime endTime);

    SkillVO updateSkill(Long id, CreateSkillDTO dto);

    /** 运行线程内更新技能时显式指定更新人（避免审计回落为 system）。 */
    SkillVO updateSkill(Long id, CreateSkillDTO dto, String updatedBy);

    void deleteSkill(Long id);

    void batchDeleteSkill(java.util.List<Long> ids);

    /** 单个启用/禁用。 */
    SkillVO updateStatus(Long id, String status);

    /** 批量启用/禁用。 */
    void batchUpdateStatus(java.util.List<Long> ids, String status);

    List<SkillVO> listSkillsByIds(List<Long> ids);

    /** 设置自己 skill 的 hub 可见性（PRIVATE/PUBLIC）。 */
    SkillVO setVisibility(Long id, String visibility);

    /**
     * 从 hub 安装：将源 skill 复制一份到当前用户名下（fork，不绑定实例）。
     * 源须为公开或自己所有；副本默认私有，同名自动加后缀。
     */
    SkillVO installSkill(Long sourceId, String newName);

    /** hub 列表：公开的全部 + 自己的全部（分页）。 */
    com.baomidou.mybatisplus.core.metadata.IPage<SkillVO> pageHubSkills(int page, int size, String keyword);
}
