package com.buukle.agent.resource.template.init;

import com.buukle.agent.capability.skill.dtvo.dto.CreateSkillDTO;
import com.buukle.agent.capability.skill.dtvo.vo.SkillVO;
import com.buukle.agent.capability.skill.spi.CapabilitySkillSpi;
import com.buukle.agent.resource.template.ResourceExistsException;
import com.buukle.agent.resource.template.ResourceInitContext;
import com.buukle.agent.resource.template.ResourceInitializer;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
@RequiredArgsConstructor
public class SkillInitializer implements ResourceInitializer {

    private static final String TYPE = "skill";

    private final CapabilitySkillSpi skillSpi;

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public void initialize(JsonNode descriptor, ResourceInitContext ctx) {
        String name = descriptor.path("name").asText();
        if (!StringUtils.hasText(name)) {
            return;
        }
        boolean exists = skillSpi.listSkills(name, null, null).stream()
                .anyMatch(s -> name.equals(s.getName()));
        if (exists) {
            throw new ResourceExistsException();
        }
        CreateSkillDTO dto = new CreateSkillDTO();
        dto.setName(name);
        String description = descriptor.path("description").asText(null);
        // 模板未带 description 时用 name 兜底，避免工具描述为空
        dto.setDescription(StringUtils.hasText(description) ? description : name);
        dto.setDefinition(descriptor.path("definition").asText(null));
        skillSpi.createSkill(dto);
    }
}
