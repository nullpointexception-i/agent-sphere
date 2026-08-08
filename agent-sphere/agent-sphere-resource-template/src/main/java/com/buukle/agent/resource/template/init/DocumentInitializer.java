package com.buukle.agent.resource.template.init;

import com.buukle.agent.instance.dtvo.vo.DocumentVO;
import com.buukle.agent.instance.spi.DocumentSpi;
import com.buukle.agent.resource.template.ResourceExistsException;
import com.buukle.agent.resource.template.ResourceInitContext;
import com.buukle.agent.resource.template.ResourceInitializer;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
@RequiredArgsConstructor
public class DocumentInitializer implements ResourceInitializer {

    private static final String TYPE = "document";

    private final DocumentSpi documentSpi;

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public void initialize(JsonNode descriptor, ResourceInitContext ctx) {
        String title = descriptor.path("title").asText();
        if (!StringUtils.hasText(title)) {
            return;
        }
        boolean exists = documentSpi.listAll(1, 100).getRecords().stream()
                .anyMatch(d -> title.equals(d.getTitle()));
        if (exists) {
            throw new ResourceExistsException();
        }
        DocumentVO vo = new DocumentVO();
        vo.setTitle(title);
        vo.setContent(descriptor.path("content").asText(""));
        vo.setContentType(descriptor.path("contentType").asText("text"));
        documentSpi.create(vo);
    }
}
