package com.buukle.agent.capability.skill.spi;

import com.buukle.agent.capability.skill.dtvo.vo.SkillVO;

import java.util.List;

/**
 * Read-only access to tenant hub entries for the runtime.
 *
 * <p>Only instance bindings of type {@code skill_hub} reach this SPI; the binding already scopes
 * the reference to one instance, so the ids are trusted and no tenant filter is applied here.
 */
public interface SkillHubSpi {

    /** Hub entries by id, in the same order-agnostic shape the skill library expects. */
    List<SkillVO> listHubSkillsByIds(List<Long> hubIds);

    /** One hub entry, or null when it no longer exists. */
    SkillVO getHubSkill(Long hubId);
}
