package com.buukle.agent.sso.service;

import com.buukle.agent.sso.dtvo.dto.CreateIdentityProviderDTO;
import com.buukle.agent.sso.dtvo.dto.UpdateIdentityProviderDTO;
import com.buukle.agent.sso.dtvo.vo.IdentityProviderVO;
import com.buukle.agent.sso.dtvo.vo.ResourceTemplateVO;

import java.util.List;

public interface IdentityProviderService {

    IdentityProviderVO createProvider(CreateIdentityProviderDTO dto);

    IdentityProviderVO getProvider(Long id);

    List<IdentityProviderVO> listProviders(String keyword);

    IdentityProviderVO updateProvider(Long id, UpdateIdentityProviderDTO dto);

    void deleteProvider(Long id);

    void setEnabled(Long id, Boolean enabled);

    void testConnection(Long id);

    ResourceTemplateVO getDefaultResourceTemplate();
}