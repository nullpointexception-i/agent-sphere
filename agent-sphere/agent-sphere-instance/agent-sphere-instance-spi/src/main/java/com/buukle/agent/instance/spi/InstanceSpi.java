package com.buukle.agent.instance.spi;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.buukle.agent.instance.dtvo.dto.CreateInstanceDTO;
import com.buukle.agent.instance.dtvo.vo.InstanceVO;

import java.time.LocalDateTime;
import java.util.List;

public interface InstanceSpi {
    InstanceVO createInstance(CreateInstanceDTO dto);

    InstanceVO getInstance(Long id);

    /** 按实例角色查一个启用中的系统实例（如 GROUP_ROUTER）。 */
    InstanceVO findByRole(String instanceRole);

    InstanceVO updateInstance(Long id, CreateInstanceDTO dto);

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

    List<InstanceVO> listLatestNInstances(int n);

    InstanceVO setModelRoute(Long id, Long modelRouteId);
}
