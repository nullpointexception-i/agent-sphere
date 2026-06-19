package com.buukle.agent.capability.cli.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.buukle.agent.capability.cli.domain.CapabilityCli;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface CliMapper extends BaseMapper<CapabilityCli> {}
