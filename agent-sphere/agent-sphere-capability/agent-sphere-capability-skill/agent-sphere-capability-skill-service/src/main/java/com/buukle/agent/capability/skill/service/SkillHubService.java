package com.buukle.agent.capability.skill.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.buukle.agent.capability.skill.dtvo.vo.HubPublishStateVO;
import com.buukle.agent.capability.skill.dtvo.vo.HubSkillVO;
import com.buukle.agent.capability.skill.dtvo.vo.SkillVO;

import java.util.List;

/**
 * Tenant-scoped Skill Hub: publish from a local skill, browse the tenant + ancestor hubs,
 * follow without copying, or install a copy.
 */
public interface SkillHubService {

    /**
     * Publish (or re-publish) one local skill into its tenant hub.
     *
     * <p>Each call bumps the hub version by one, even when the content is identical or when the
     * author restored older content. There is deliberately no withdraw operation.
     */
    HubSkillVO publish(Long skillId);

    /** Publish state of one local skill (used for the "my skills" badges). */
    HubPublishStateVO publishState(Long skillId);

    /** Hub entries of the current tenant plus every ancestor tenant. */
    IPage<HubSkillVO> pageHubSkills(int page, int size, String keyword);

    /** One hub entry with its definition, when it is visible to the current tenant. */
    HubSkillVO getHub(Long hubId);

    /** Hub entries the current user follows. */
    List<HubSkillVO> listFollowed();

    /** Subscribe to a hub entry without creating a local copy. */
    void follow(Long hubId);

    /** Remove the subscription; installed copies and instance bindings are unaffected. */
    void unfollow(Long hubId);

    /** Install a hub entry as a local copy; {@code autoUpdate} keeps it aligned with the hub. */
    SkillVO install(Long hubId, Boolean autoUpdate);
}
