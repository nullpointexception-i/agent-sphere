package com.buukle.agent.superagent.service.impl;

import com.buukle.agent.common.error.CommonErrorCode;
import com.buukle.agent.common.exception.BizException;
import com.buukle.agent.instance.dtvo.dto.CreateInstanceDTO;
import com.buukle.agent.instance.dtvo.vo.InstanceVO;
import com.buukle.agent.instance.spi.InstanceSpi;
import com.buukle.agent.superagent.dtvo.vo.GroupRouterTemplateVO;
import com.buukle.agent.superagent.service.GroupRouterTemplateService;
import com.buukle.agent.superagent.service.support.GroupRouterTemplate;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class GroupRouterTemplateServiceImpl implements GroupRouterTemplateService {

    private static final String ROLE_GROUP_ROUTER = "GROUP_ROUTER";

    private final InstanceSpi instanceSpi;

    @Override
    public GroupRouterTemplateVO get() {
        InstanceVO instance = instanceSpi.findByRole(ROLE_GROUP_ROUTER);
        boolean custom = instance != null && instance.getSystemPrompt() != null
                && !instance.getSystemPrompt().isBlank();
        GroupRouterTemplateVO vo = new GroupRouterTemplateVO();
        vo.setInstanceId(instance == null ? null : instance.getId());
        vo.setTemplate(custom ? instance.getSystemPrompt() : GroupRouterTemplate.DEFAULT);
        vo.setDefaultTemplate(GroupRouterTemplate.DEFAULT);
        vo.setCustom(custom);
        return vo;
    }

    @Override
    public GroupRouterTemplateVO update(String template) {
        InstanceVO instance = instanceSpi.findByRole(ROLE_GROUP_ROUTER);
        if (instance == null) {
            throw new BizException(CommonErrorCode.RESOURCE_NOT_FOUND,
                    "group router instance not found; enable super-agent and configure a model route first");
        }
        CreateInstanceDTO dto = new CreateInstanceDTO();
        dto.setName(instance.getName());
        dto.setSystemPrompt(template);
        instanceSpi.updateInstance(instance.getId(), dto);
        return get();
    }
}
