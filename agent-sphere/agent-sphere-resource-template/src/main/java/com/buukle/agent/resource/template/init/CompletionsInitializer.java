package com.buukle.agent.resource.template.init;

import com.buukle.agent.completions.dtvo.CompletionsPromptVO;
import com.buukle.agent.completions.dtvo.CompletionsVO;
import com.buukle.agent.completions.dtvo.CreateCompletionsDTO;
import com.buukle.agent.completions.dtvo.CreatePromptDTO;
import com.buukle.agent.completions.service.CompletionsPromptService;
import com.buukle.agent.completions.service.CompletionsService;
import com.buukle.agent.resource.template.ResourceExistsException;
import com.buukle.agent.resource.template.ResourceInitContext;
import com.buukle.agent.resource.template.ResourceInitializer;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
@RequiredArgsConstructor
public class CompletionsInitializer implements ResourceInitializer {

    private static final String TYPE = "completions";

    private final CompletionsService completionsService;
    private final CompletionsPromptService completionsPromptService;

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
        boolean exists = completionsService.list(name, null, null, 1, 20).getRecords().stream()
                .anyMatch(c -> name.equals(c.getName()));
        if (exists) {
            throw new ResourceExistsException();
        }
        CreateCompletionsDTO dto = new CreateCompletionsDTO();
        dto.setName(name);
        dto.setDescription(descriptor.path("description").asText(null));
        dto.setBusinessType(descriptor.path("businessType").asText(null));
        dto.setConfig(descriptor.path("config").asText(null));
        dto.setInputSchema(descriptor.path("inputSchema").asText(null));
        dto.setOutputSchema(descriptor.path("outputSchema").asText(null));
        String routeName = descriptor.path("route").asText(null);
        if (StringUtils.hasText(routeName)) {
            dto.setModelRouteId(ctx.get("model_route", routeName));
        }
        CompletionsVO vo = completionsService.create(dto);

        String promptSystem = descriptor.path("promptSystem").asText(null);
        String promptUser = descriptor.path("promptUser").asText(null);
        if (StringUtils.hasText(promptUser)) {
            CreatePromptDTO promptDTO = new CreatePromptDTO();
            promptDTO.setPromptSystem(promptSystem);
            promptDTO.setPromptUser(promptUser);
            CompletionsPromptVO prompt = completionsPromptService.addVersion(vo.getId(), promptDTO);
            completionsPromptService.activate(vo.getId(), prompt.getId());
        }
        ctx.put(TYPE, name, vo.getId());
    }
}
