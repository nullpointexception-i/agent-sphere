package com.buukle.agent.sso.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.buukle.agent.admin.spi.RoleSpi;
import com.buukle.agent.admin.spi.TenantProvisioningSpi;
import com.buukle.agent.admin.spi.TenantProvisioningSpi.ProvisionedTenant;
import com.buukle.agent.common.config.SystemConfigKeys;
import com.buukle.agent.common.config.SystemConfigSpi;
import com.buukle.agent.common.exception.BizException;
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
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class IdentityProviderServiceImpl extends ServiceImpl<IdentityProviderMapper, IdentityProvider>
        implements IdentityProviderService {

    private final IdentityProviderConverter identityProviderConverter;
    private final SsoOidcClient ssoOidcClient;
    private final SystemConfigSpi systemConfigSpi;
    private final TenantProvisioningSpi tenantProvisioningSpi;
    private final RoleSpi roleSpi;

    @Override
    @Transactional
    public IdentityProviderVO createProvider(CreateIdentityProviderDTO dto) {
        long count = lambdaQuery().eq(IdentityProvider::getCode, dto.getCode()).count();
        if (count > 0) {
            throw new BizException(SsoErrorCode.PROVIDER_CODE_EXISTS);
        }
        ProvisionedTenant tenant = tenantProvisioningSpi.createUnderPlatform(
                "SSO_" + dto.getCode(),
                dto.getName(),
                "Auto-created for identity provider " + dto.getCode());
        IdentityProvider provider = identityProviderConverter.toDO(dto);
        provider.setTenantId(tenant.tenantId());
        provider.setDefaultRoleId(tenant.userRoleId());
        save(provider);
        return toVO(provider);
    }

    @Override
    public IdentityProviderVO getProvider(Long id) {
        return toVO(requireProvider(id));
    }

    @Override
    public ResourceTemplateVO getDefaultResourceTemplate() {
        String template = systemConfigSpi.get(SystemConfigKeys.USER_RESOURCE_TEMPLATE, "");
        return new ResourceTemplateVO(template);
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
        return providers.stream().map(this::toVO).toList();
    }

    @Override
    public IdentityProviderVO updateProvider(Long id, UpdateIdentityProviderDTO dto) {
        IdentityProvider existing = requireProvider(id);
        if (dto.getDefaultRoleId() != null && existing.getTenantId() != null) {
            Long roleTenantId = roleSpi.findTenantIdByRoleId(dto.getDefaultRoleId());
            if (!existing.getTenantId().equals(roleTenantId)) {
                throw new BizException(SsoErrorCode.DEFAULT_ROLE_SCOPE_INVALID);
            }
        }
        identityProviderConverter.applyUpdate(existing, dto);
        updateById(existing);
        return toVO(existing);
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

    private IdentityProviderVO toVO(IdentityProvider provider) {
        IdentityProviderVO vo = identityProviderConverter.toVO(provider);
        if (vo != null && provider.getTenantId() != null) {
            vo.setTenantName(tenantProvisioningSpi.findTenantName(provider.getTenantId()));
        }
        return vo;
    }
}