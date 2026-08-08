package com.buukle.agent.sso.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.buukle.agent.common.exception.BizException;
import com.buukle.agent.resource.template.ResourceTemplates;
import com.buukle.agent.sso.domain.IdentityProvider;
import com.buukle.agent.sso.dtvo.dto.CreateIdentityProviderDTO;
import com.buukle.agent.sso.dtvo.dto.UpdateIdentityProviderDTO;
import com.buukle.agent.sso.dtvo.vo.IdentityProviderVO;
import com.buukle.agent.sso.dtvo.vo.ResourceTemplateVO;
import com.buukle.agent.sso.exception.SsoErrorCode;
import com.buukle.agent.sso.repository.IdentityProviderMapper;
import com.buukle.agent.sso.service.IdentityProviderService;
import com.buukle.agent.sso.service.SsoOidcClient;
import com.buukle.agent.sso.service.converter.IdentityProviderConverter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class IdentityProviderServiceImpl extends ServiceImpl<IdentityProviderMapper, IdentityProvider>
        implements IdentityProviderService {

    private final IdentityProviderConverter identityProviderConverter;
    private final SsoOidcClient ssoOidcClient;
    @Override
    public IdentityProviderVO createProvider(CreateIdentityProviderDTO dto) {
        long count = lambdaQuery().eq(IdentityProvider::getCode, dto.getCode()).count();
        if (count > 0) {
            throw new BizException(SsoErrorCode.PROVIDER_CODE_EXISTS);
        }
        IdentityProvider provider = identityProviderConverter.toDO(dto);
        save(provider);
        return identityProviderConverter.toVO(provider);
    }

    @Override
    public IdentityProviderVO getProvider(Long id) {
        return identityProviderConverter.toVO(requireProvider(id));
    }

    @Override
    public ResourceTemplateVO getDefaultResourceTemplate() {
        return new ResourceTemplateVO(ResourceTemplates.DEFAULT);
    }

    @Override
    public List<IdentityProviderVO> listProviders(String keyword) {
        List<IdentityProvider> providers = lambdaQuery()
                .and(keyword != null && !keyword.isBlank(),
                        w -> w.like(IdentityProvider::getName, keyword)
                                .or()
                                .like(IdentityProvider::getCode, keyword))
                .orderByDesc(IdentityProvider::getCreatedAt)
                .list();
        return providers.stream().map(identityProviderConverter::toVO).toList();
    }

    @Override
    public IdentityProviderVO updateProvider(Long id, UpdateIdentityProviderDTO dto) {
        IdentityProvider existing = requireProvider(id);
        identityProviderConverter.applyUpdate(existing, dto);
        updateById(existing);
        return identityProviderConverter.toVO(existing);
    }

    @Override
    public void deleteProvider(Long id) {
        requireProvider(id);
        removeById(id);
    }

    @Override
    public void setEnabled(Long id, Boolean enabled) {
        requireProvider(id);
        lambdaUpdate().eq(IdentityProvider::getId, id)
                .set(IdentityProvider::getEnabled, enabled)
                .update();
    }

    @Override
    public void testConnection(Long id) {
        ssoOidcClient.testConnection(requireProvider(id));
    }

    private IdentityProvider requireProvider(Long id) {
        IdentityProvider provider = getById(id);
        if (provider == null) {
            throw new BizException(SsoErrorCode.PROVIDER_NOT_FOUND);
        }
        return provider;
    }
}