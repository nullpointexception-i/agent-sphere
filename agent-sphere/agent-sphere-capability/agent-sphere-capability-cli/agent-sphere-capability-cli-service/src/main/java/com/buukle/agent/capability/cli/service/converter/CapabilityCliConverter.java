package com.buukle.agent.capability.cli.service.converter;

import com.buukle.agent.capability.cli.domain.CapabilityCli;
import com.buukle.agent.capability.cli.dtvo.dto.CreateCliDTO;
import com.buukle.agent.capability.cli.dtvo.enums.CliCapabilityEnum;
import com.buukle.agent.capability.cli.dtvo.vo.CliVO;
import org.springframework.stereotype.Component;

import java.time.format.DateTimeFormatter;

@Component
public class CapabilityCliConverter {
    private static final DateTimeFormatter DTF = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    public CliVO toVO(CapabilityCli cli) {
        if (cli == null) return null;
        CliVO vo = new CliVO();
        vo.setId(cli.getId());
        vo.setName(cli.getName());
        vo.setCommandTemplate(cli.getCommandTemplate());
        vo.setParamSchema(cli.getParamSchema());
        vo.setWorkingDir(cli.getWorkingDir());
        vo.setStatus(cli.getStatus());
        vo.setCreatedAt(cli.getCreatedAt() != null ? cli.getCreatedAt().format(DTF) : null);
        vo.setCreatedBy(cli.getCreatedBy());
        vo.setUpdatedBy(cli.getUpdatedBy());
        vo.setUpdatedAt(cli.getUpdatedAt() != null ? cli.getUpdatedAt().format(DTF) : null);
        return vo;
    }

    public CapabilityCli toDO(CreateCliDTO dto) {
        CapabilityCli cli = new CapabilityCli();
        cli.setName(dto.getName());
        cli.setCommandTemplate(dto.getCommandTemplate());
        cli.setParamSchema(dto.getParamSchema());
        cli.setWorkingDir(dto.getWorkingDir());
        cli.setStatus(CliCapabilityEnum.STATUS_ENABLED);
        return cli;
    }
}
