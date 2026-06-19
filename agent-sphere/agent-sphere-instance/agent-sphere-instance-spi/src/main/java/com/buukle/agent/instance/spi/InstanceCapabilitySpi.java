package com.buukle.agent.instance.spi;

import com.buukle.agent.instance.dtvo.dto.BatchCreateInstanceCapabilityDTO;
import com.buukle.agent.instance.dtvo.dto.CreateInstanceCapabilityDTO;
import com.buukle.agent.instance.dtvo.vo.CapabilityVO;
import java.util.List;

public interface InstanceCapabilitySpi {
    List<CapabilityVO> getCapabilitiesByInstance(Long instanceId);
    CapabilityVO createCapability(CreateInstanceCapabilityDTO dto);
    void deleteCapability(Long id);
    List<CapabilityVO> batchCreateCapabilities(BatchCreateInstanceCapabilityDTO dto);
}
