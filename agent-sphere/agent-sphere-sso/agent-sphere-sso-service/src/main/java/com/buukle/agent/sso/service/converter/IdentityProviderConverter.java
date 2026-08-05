package com.buukle.agent.sso.service.converter;

import com.buukle.agent.common.security.CryptoService;
import com.buukle.agent.sso.domain.IdentityProvider;
import com.buukle.agent.sso.domain.SsoProviderType;
import com.buukle.agent.sso.dtvo.dto.CreateIdentityProviderDTO;
import com.buukle.agent.sso.dtvo.dto.UpdateIdentityProviderDTO;
import com.buukle.agent.sso.dtvo.enums.SsoProviderEnum;
import com.buukle.agent.sso.dtvo.vo.IdentityProviderVO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.format.DateTimeFormatter;

@Component
@RequiredArgsConstructor
public class IdentityProviderConverter {

    private static final DateTimeFormatter DTF = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final String SECRET_MASK = "****";

    private final CryptoService cryptoService;

    public IdentityProviderVO toVO(IdentityProvider provider) {
        if (provider == null) {
            return null;
        }
        IdentityProviderVO vo = new IdentityProviderVO();
        vo.setId(provider.getId());
        vo.setCode(provider.getCode());
        vo.setType(provider.getType());
        vo.setName(provider.getName());
        vo.setIssuer(provider.getIssuer());
        vo.setClientId(provider.getClientId());
        boolean hasSecret = provider.getClientSecret() != null && !provider.getClientSecret().isBlank();
        vo.setHasSecret(hasSecret);
        vo.setClientSecret(hasSecret ? SECRET_MASK : null);
        vo.setAuthorizationEndpoint(provider.getAuthorizationEndpoint());
        vo.setTokenEndpoint(provider.getTokenEndpoint());
        vo.setJwksUrl(provider.getJwksUrl());
        vo.setScopes(provider.getScopes());
        vo.setClaimMappings(provider.getClaimMappings());
        vo.setDefaultRoleId(provider.getDefaultRoleId());
        vo.setEnabled(provider.getEnabled());
        vo.setStatus(provider.getStatus());
        vo.setRemark(provider.getRemark());
        vo.setCreatedAt(provider.getCreatedAt() != null ? provider.getCreatedAt().format(DTF) : null);
        vo.setCreatedBy(provider.getCreatedBy());
        vo.setUpdatedBy(provider.getUpdatedBy());
        vo.setUpdatedAt(provider.getUpdatedAt() != null ? provider.getUpdatedAt().format(DTF) : null);
        return vo;
    }

    public IdentityProvider toDO(CreateIdentityProviderDTO dto) {
        IdentityProvider provider = new IdentityProvider();
        provider.setCode(dto.getCode());
        provider.setType(SsoProviderType.OIDC.getValue());
        provider.setName(dto.getName());
        provider.setIssuer(dto.getIssuer());
        provider.setClientId(dto.getClientId());
        provider.setClientSecret(cryptoService.encrypt(dto.getClientSecret()));
        provider.setAuthorizationEndpoint(dto.getAuthorizationEndpoint());
        provider.setTokenEndpoint(dto.getTokenEndpoint());
        provider.setJwksUrl(dto.getJwksUrl());
        provider.setScopes(dto.getScopes());
        provider.setClaimMappings(dto.getClaimMappings());
        provider.setDefaultRoleId(dto.getDefaultRoleId());
        provider.setEnabled(dto.getEnabled() == null ? Boolean.TRUE : dto.getEnabled());
        provider.setStatus(SsoProviderEnum.STATUS_ACTIVE);
        provider.setRemark(dto.getRemark());
        return provider;
    }

    public void applyUpdate(IdentityProvider existing, UpdateIdentityProviderDTO dto) {
        existing.setName(dto.getName());
        existing.setIssuer(dto.getIssuer());
        existing.setClientId(dto.getClientId());
        existing.setAuthorizationEndpoint(dto.getAuthorizationEndpoint());
        existing.setTokenEndpoint(dto.getTokenEndpoint());
        existing.setJwksUrl(dto.getJwksUrl());
        existing.setScopes(dto.getScopes());
        existing.setClaimMappings(dto.getClaimMappings());
        existing.setDefaultRoleId(dto.getDefaultRoleId());
        existing.setRemark(dto.getRemark());
        if (dto.getClientSecret() != null && !dto.getClientSecret().isBlank()) {
            existing.setClientSecret(cryptoService.encrypt(dto.getClientSecret()));
        }
    }
}