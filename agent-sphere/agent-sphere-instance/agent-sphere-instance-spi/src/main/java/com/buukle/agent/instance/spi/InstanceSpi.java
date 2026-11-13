package com.buukle.agent.instance.spi;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.buukle.agent.instance.dtvo.dto.CreateInstanceDTO;
import com.buukle.agent.instance.dtvo.vo.InstanceVO;

import java.time.LocalDateTime;
import java.util.List;

public interface InstanceSpi {
    InstanceVO createInstance(CreateInstanceDTO dto);

    InstanceVO getInstance(Long id);

    /** Cross-user read of a tenant-owned instance (project assistant canonical/fork). */
    InstanceVO getInstanceShared(Long id);

    InstanceVO updateInstance(Long id, CreateInstanceDTO dto);

    /** Targeted update: refresh a managed instance's system prompt without touching other columns. */
    void updateSystemPrompt(Long id, String systemPrompt);

    void deleteInstance(Long id);

    void batchDeleteInstances(List<Long> ids);

    List<InstanceVO> listInstances(String keyword, LocalDateTime startTime, LocalDateTime endTime);

    IPage<InstanceVO> pageInstances(int page, int size, String keyword, LocalDateTime startTime, LocalDateTime endTime);

    /**
     * Page instances filtered by role. {@code null}/blank means CHAT; {@code ALL} returns
     * every non system-managed instance.
     */
    IPage<InstanceVO> pageInstances(int page, int size, String keyword, LocalDateTime startTime,
                                    LocalDateTime endTime, String instanceRole);

    /**
     * @param scope BUSINESS (default, excludes system resources) or SYSTEM
     *              (only tenant-owned instances; role defaults to ALL).
     */
    IPage<InstanceVO> pageInstances(int page, int size, String keyword, LocalDateTime startTime,
                                    LocalDateTime endTime, String instanceRole, String scope);

    /** The tenant's current GROUP_ROUTER instance, or null. */
    InstanceVO findCurrentGroupRouter(Long tenantId);

    /** A tenant-scoped instance by role (CHAT / GROUP_ROUTER / PROJECT_ROUTER ...), or null. */
    InstanceVO findByRole(String role);

    /** Idempotently provision the tenant-level PROJECT_ROUTER; null when no model route exists. */
    InstanceVO ensureProjectRouter(Long tenantId);

    List<InstanceVO> listLatestNInstances(int n);

    InstanceVO setModelRoute(Long id, Long modelRouteId);
}
