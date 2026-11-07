package com.buukle.agent.capability.skill.spi;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.buukle.agent.capability.skill.dtvo.dto.CreateSkillDTO;
import com.buukle.agent.capability.skill.dtvo.vo.SkillVO;

import java.time.LocalDateTime;
import java.util.List;

public interface CapabilitySkillSpi {
    SkillVO createSkill(CreateSkillDTO dto);

    /** Explicitly sets the creator when creating a skill on a run thread (otherwise audit falls back to system). */
    SkillVO createSkill(CreateSkillDTO dto, String createdBy);

    SkillVO getSkill(Long id);

    List<SkillVO> listSkills(String keyword, LocalDateTime startTime, LocalDateTime endTime);

    IPage<SkillVO> pageSkills(int page, int size, String keyword, LocalDateTime startTime, LocalDateTime endTime);

    SkillVO updateSkill(Long id, CreateSkillDTO dto);

    /** Explicitly sets the updater when updating a skill on a run thread (otherwise audit falls back to system). */
    SkillVO updateSkill(Long id, CreateSkillDTO dto, String updatedBy);

    void deleteSkill(Long id);

    void batchDeleteSkill(java.util.List<Long> ids);

    /** Enable/disable a single skill. */
    SkillVO updateStatus(Long id, String status);

    /** Enable/disable in bulk. */
    void batchUpdateStatus(java.util.List<Long> ids, String status);

    List<SkillVO> listSkillsByIds(List<Long> ids);

    /** Sets the hub visibility of one of the caller's own skills (PRIVATE/PUBLIC). */
    SkillVO setVisibility(Long id, String visibility);

    /**
     * Install from the hub: copy the source skill into the current user's namespace (fork, no
     * instance binding). The source must be public or owned by the caller; the copy is private by
     * default and gets a suffix automatically when the name collides.
     */
    SkillVO installSkill(Long sourceId, String newName);

    /** Hub listing: all public skills plus all of the caller's own (paged). */
    com.baomidou.mybatisplus.core.metadata.IPage<SkillVO> pageHubSkills(int page, int size, String keyword);

    /** Toggles auto-update for one of the caller's installed copies (copies only). */
    SkillVO setAutoUpdate(Long id, boolean enabled);
}
