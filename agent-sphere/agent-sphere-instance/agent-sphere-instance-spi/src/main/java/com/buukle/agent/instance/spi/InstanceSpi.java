package com.buukle.agent.instance.spi;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.buukle.agent.instance.dtvo.dto.CreateInstanceDTO;
import com.buukle.agent.instance.dtvo.vo.InstanceVO;

import java.time.LocalDateTime;
import java.util.List;

public interface InstanceSpi {
    InstanceVO getInstance(Long id);

    InstanceVO updateInstance(Long id, CreateInstanceDTO dto);

    void deleteInstance(Long id);

    void batchDeleteInstances(List<Long> ids);

    List<InstanceVO> listInstances(String keyword, LocalDateTime startTime, LocalDateTime endTime);

    IPage<InstanceVO> pageInstances(int page, int size, String keyword, LocalDateTime startTime, LocalDateTime endTime);

    List<InstanceVO> listLatestNInstances(int n);

    InstanceVO setModelRoute(Long id, Long modelRouteId);
}
