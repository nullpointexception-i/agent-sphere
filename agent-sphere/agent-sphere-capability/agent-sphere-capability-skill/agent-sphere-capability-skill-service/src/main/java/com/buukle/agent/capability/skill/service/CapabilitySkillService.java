package com.buukle.agent.capability.skill.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.buukle.agent.capability.skill.domain.CapabilitySkill;
import com.buukle.agent.capability.skill.dtvo.enums.SkillSyncOutcome;
import com.buukle.agent.capability.skill.spi.CapabilitySkillSpi;

public interface CapabilitySkillService extends IService<CapabilitySkill>, CapabilitySkillSpi {

    /**
     * Sync one installed copy from its origin version and return the <b>specific outcome</b>
     * (including the skip reason).
     *
     * <p>On success it also writes the copy's {@code synced_at} and {@code synced_from_version}
     * display snapshots; {@code origin_version} is still advanced by the conditional UPDATE, so the
     * values agree without affecting each other.
     *
     * @param copy installed copy (origin_skill_id non-null, auto_update=true)
     */
    SkillSyncOutcome syncWithOutcome(CapabilitySkill copy);

    /**
     * Sync one installed copy from its origin version (use this when only success/failure matters).
     *
     * @deprecated Too coarse: a skip cannot be attributed to a cause. Use {@link #syncWithOutcome}.
     */
    @Deprecated
    boolean syncFromOrigin(CapabilitySkill copy);
}