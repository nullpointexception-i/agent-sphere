package com.buukle.agent.application.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.buukle.agent.application.domain.BizApplication;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface BizApplicationMapper extends BaseMapper<BizApplication> {
}
