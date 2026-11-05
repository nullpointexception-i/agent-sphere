package com.buukle.agent.superagent.controller;

import com.buukle.agent.channel.spi.EventIngestPort;
import com.buukle.agent.channel.spi.NormalizedEvent;
import com.buukle.agent.common.util.BaseController;
import com.buukle.agent.common.util.TokenHasher;
import com.buukle.agent.superagent.domain.AgentChannelBinding;
import com.buukle.agent.superagent.dtvo.enums.AgentChannelBindingStatus;
import com.buukle.agent.superagent.service.ChannelBindingService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Generic webhook ingress for channel bindings. */
@RestController
@RequestMapping("/api/v1/channels")
@RequiredArgsConstructor
public class ChannelWebhookController extends BaseController {

    private static final String SIGNATURE_HEADER = "X-Channel-Signature";
    private static final ObjectMapper JSON = new ObjectMapper();

    private final ChannelBindingService bindingService;
    private final EventIngestPort eventIngestPort;

    @PostMapping("/{bindingId}/inbound")
    public ResponseEntity<?> inbound(@PathVariable Long bindingId,
                                     @RequestHeader(value = SIGNATURE_HEADER, required = false) String signature,
                                     @RequestBody String body) {
        AgentChannelBinding binding = bindingService.get(bindingId);
        if (!AgentChannelBindingStatus.ACTIVE.equals(binding.getStatus())) {
            return ResponseEntity.badRequest().body("channel binding is disabled");
        }
        if (binding.getInboundTokenHash() != null && !binding.getInboundTokenHash().isBlank()) {
            if (signature == null || !binding.getInboundTokenHash()
                    .equals(TokenHasher.sha256(signature))) {
                return ResponseEntity.status(401).body("invalid signature");
            }
        }
        try {
            JsonNode root = JSON.readTree(body);
            String externalThreadId = root.path("externalThreadId").asText(null);
            String externalEventId = root.path("externalEventId").asText(null);
            String text = root.path("text").asText("");
            String author = root.path("author").asText(null);
            var result = eventIngestPort.ingest(new NormalizedEvent(
                    binding.getChannelType(),
                    externalThreadId,
                    externalEventId,
                    "MESSAGE",
                    author,
                    false,
                    text,
                    List.of(),
                    root.path("rawMeta").asText(null),
                    binding.getId()));
            return ok(result);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body("invalid webhook payload");
        }
    }
}
