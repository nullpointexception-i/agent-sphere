package com.buukle.agent.sso.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.buukle.agent.sso.domain.IdentityProvider;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface IdentityProviderMapper extends BaseMapper<IdentityProvider> {
}