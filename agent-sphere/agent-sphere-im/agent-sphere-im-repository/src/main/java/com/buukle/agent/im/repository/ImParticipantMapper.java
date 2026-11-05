package com.buukle.agent.im.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import com.buukle.agent.im.domain.ImParticipant;

@Mapper
public interface ImParticipantMapper extends BaseMapper<ImParticipant> {
}
