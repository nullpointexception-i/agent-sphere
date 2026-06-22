package com.buukle.agent.capability.cli.service.impl;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.buukle.agent.capability.cli.domain.CapabilityCli;
import com.buukle.agent.capability.cli.dtvo.dto.CreateCliDTO;
import com.buukle.agent.capability.cli.dtvo.vo.CliVO;
import com.buukle.agent.capability.cli.exception.CapabilityCliErrorCode;
import com.buukle.agent.capability.cli.repository.CliMapper;
import com.buukle.agent.capability.cli.service.CapabilityCliService;
import com.buukle.agent.capability.cli.service.converter.CapabilityCliConverter;
import com.buukle.agent.common.exception.BizException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
@Primary
public class CapabilityCliServiceImpl extends ServiceImpl<CliMapper, CapabilityCli> implements CapabilityCliService {
    private final CapabilityCliConverter capabilityCliConverter;

    @Override
    public CliVO createCli(CreateCliDTO dto) {
        CapabilityCli cli = capabilityCliConverter.toDO(dto);
        save(cli);
        return capabilityCliConverter.toVO(cli);
    }

    @Override
    public CliVO getCli(Long id) {
        CapabilityCli cli = getById(id);
        if (cli == null) throw new BizException(CapabilityCliErrorCode.CLI_NOT_FOUND);
        return capabilityCliConverter.toVO(cli);
    }

    @Override
    public CliVO updateCli(Long id, CreateCliDTO dto) {
        CapabilityCli cli = capabilityCliConverter.toDO(dto);
        cli.setId(id);
        updateById(cli);
        return capabilityCliConverter.toVO(cli);
    }

    @Override
    public void deleteCli(Long id) {
        removeById(id);
    }

    @Override
    public void batchDeleteCli(java.util.List<Long> ids) {
        removeByIds(ids);
    }

    @Override
    public List<CliVO> listClis(String keyword, LocalDateTime startTime, LocalDateTime endTime) {
        log.warn("listClis called: keyword='{}', startTime={}, endTime={}", keyword, startTime, endTime);
        List<CapabilityCli> list = lambdaQuery()
                .like(keyword != null && !keyword.isBlank(), CapabilityCli::getName, keyword)
                .ge(startTime != null, CapabilityCli::getCreatedAt, startTime)
                .le(endTime != null, CapabilityCli::getCreatedAt, endTime)
                .orderByDesc(CapabilityCli::getCreatedAt)
                .list();
        log.warn("listClis result: {} rows", list.size());
        return list.stream().map(capabilityCliConverter::toVO).toList();
    }

    @Override
    public IPage<CliVO> pageClis(int page, int size, String keyword, LocalDateTime startTime, LocalDateTime endTime) {
        Page<CapabilityCli> p = lambdaQuery()
                .like(keyword != null && !keyword.isBlank(), CapabilityCli::getName, keyword)
                .ge(startTime != null, CapabilityCli::getCreatedAt, startTime)
                .le(endTime != null, CapabilityCli::getCreatedAt, endTime)
                .orderByDesc(CapabilityCli::getCreatedAt)
                .page(new Page<>(page, size));
        return p.convert(capabilityCliConverter::toVO);
    }

    @Override
    public List<CliVO> listClisByIds(List<Long> ids) {
        if (ids == null || ids.isEmpty()) return List.of();
        return lambdaQuery().in(CapabilityCli::getId, ids).list().stream().map(capabilityCliConverter::toVO).toList();
    }
}
