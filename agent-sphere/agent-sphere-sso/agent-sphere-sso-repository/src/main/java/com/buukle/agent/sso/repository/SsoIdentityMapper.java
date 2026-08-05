package com.buukle.agent.sso.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.buukle.agent.sso.domain.SsoIdentity;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface SsoIdentityMapper extends BaseMapper<SsoIdentity> {
}