package com.buukle.agent.capability.cli.spi;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.buukle.agent.capability.cli.dtvo.dto.CreateCliDTO;
import com.buukle.agent.capability.cli.dtvo.vo.CliVO;

import java.time.LocalDateTime;
import java.util.List;

public interface CapabilityCliSpi {
    CliVO createCli(CreateCliDTO dto);

    CliVO getCli(Long id);

    List<CliVO> listClis(String keyword, LocalDateTime startTime, LocalDateTime endTime);

    IPage<CliVO> pageClis(int page, int size, String keyword, LocalDateTime startTime, LocalDateTime endTime);

    CliVO updateCli(Long id, CreateCliDTO dto);

    void deleteCli(Long id);

    void batchDeleteCli(java.util.List<Long> ids);

    List<CliVO> listClisByIds(List<Long> ids);
}
