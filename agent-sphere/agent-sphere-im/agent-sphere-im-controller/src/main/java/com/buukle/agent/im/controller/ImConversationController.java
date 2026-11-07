package com.buukle.agent.im.controller;



import com.buukle.agent.common.constant.ApiPrefix;
import com.buukle.agent.common.constant.PermissionCodes;
import com.buukle.agent.common.annotation.AuditLog;
import com.buukle.agent.common.annotation.RequirePermission;
import com.buukle.agent.common.context.WithTenant;
import com.buukle.agent.common.util.BaseController;
import com.buukle.agent.im.dtvo.dto.AddParticipantDTO;
import com.buukle.agent.im.dtvo.dto.CreateAgentDmDTO;
import com.buukle.agent.im.dtvo.dto.CreateConversationDTO;
import com.buukle.agent.im.dtvo.dto.ImGateConfig;
import com.buukle.agent.im.dtvo.dto.MarkReadDTO;
import com.buukle.agent.im.dtvo.dto.UpdateConversationSettingDTO;
import com.buukle.agent.im.service.ImConversationService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@ConditionalOnProperty(prefix = "buukle.agent.im", name = "enabled", havingValue = "true")
@RequestMapping(ApiPrefix.V1 + "/im/conversations")
@WithTenant
@RequiredArgsConstructor
public class ImConversationController extends BaseController {

    private final ImConversationService conversationService;

    @AuditLog(action = "CREATE", resourceType = "ImConversation", resourceId = "#result?.body?.id")
    @RequirePermission(PermissionCodes.IM_CONVERSATION_CREATE)
    @PostMapping
    public ResponseEntity<?> create(@Valid @RequestBody CreateConversationDTO dto) {
        return created(conversationService.create(dto));
    }

    @AuditLog(action = "CREATE_AGENT_DM", resourceType = "ImConversation", resourceId = "#result?.body?.id")
    @RequirePermission(PermissionCodes.IM_CONVERSATION_CREATE)
    @PostMapping("/agent-dm")
    public ResponseEntity<?> createAgentDm(@Valid @RequestBody CreateAgentDmDTO dto) {
        return created(conversationService.createAgentDm(dto.getAgentId()));
    }

    @RequirePermission(PermissionCodes.IM_CONVERSATION_READ)
    @GetMapping
    public ResponseEntity<?> list(@RequestParam(defaultValue = "50") int limit,
                                  @RequestParam(required = false) String cursor,
                                  @RequestParam(required = false) String type) {
        return ok(conversationService.page(limit, cursor, type));
    }

    @RequirePermission(PermissionCodes.IM_CONVERSATION_READ)
    @GetMapping("/{id}")
    public ResponseEntity<?> get(@PathVariable Long id) {
        return ok(conversationService.get(id));
    }

    @RequirePermission(PermissionCodes.IM_CONVERSATION_UPDATE)
    @PatchMapping("/{id}")
    public ResponseEntity<?> updateMeta(@PathVariable Long id,
                                        @RequestParam(required = false) String title,
                                        @RequestParam(required = false) String purpose) {
        return ok(conversationService.updateMeta(id, title, purpose));
    }

    @RequirePermission(PermissionCodes.IM_CONVERSATION_READ)
    @GetMapping("/{id}/participants")
    public ResponseEntity<?> participants(@PathVariable Long id) {
        return ok(conversationService.participants(id));
    }

    @AuditLog(action = "ADD_PARTICIPANT", resourceType = "ImConversation", resourceId = "#id")
    @RequirePermission(PermissionCodes.IM_MEMBER_MANAGE)
    @PostMapping("/{id}/participants")
    public ResponseEntity<?> addParticipant(@PathVariable Long id,
                                            @Valid @RequestBody AddParticipantDTO dto) {
        return created(conversationService.addParticipant(id, dto));
    }

    @AuditLog(action = "REMOVE_PARTICIPANT", resourceType = "ImConversation", resourceId = "#id")
    @RequirePermission(PermissionCodes.IM_MEMBER_MANAGE)
    @DeleteMapping("/{id}/participants/{participantId}")
    public ResponseEntity<?> removeParticipant(@PathVariable Long id,
                                               @PathVariable Long participantId) {
        conversationService.removeParticipant(id, participantId);
        return deleted();
    }

    @RequirePermission(PermissionCodes.IM_CONVERSATION_READ)
    @GetMapping("/{id}/gate-config")
    public ResponseEntity<?> gateConfig(@PathVariable Long id) {
        return ok(conversationService.getGateConfig(id));
    }

    @RequirePermission(PermissionCodes.IM_CONVERSATION_UPDATE)
    @DeleteMapping("/{id}")
    public ResponseEntity<?> hide(@PathVariable Long id) {
        conversationService.hide(id);
        return deleted();
    }

    @RequirePermission(PermissionCodes.IM_CONVERSATION_READ)
    @GetMapping("/{id}/setting")
    public ResponseEntity<?> setting(@PathVariable Long id) {
        return ok(conversationService.getSetting(id));
    }

    @RequirePermission(PermissionCodes.IM_CONVERSATION_UPDATE)
    @PatchMapping("/{id}/setting")
    public ResponseEntity<?> updateSetting(@PathVariable Long id,
                                           @RequestBody UpdateConversationSettingDTO dto) {
        return ok(conversationService.updateSetting(id, dto));
    }

    @RequirePermission(PermissionCodes.IM_CONVERSATION_UPDATE)
    @PostMapping("/{id}/pinned-message/{messageId}")
    public ResponseEntity<?> pinMessage(@PathVariable Long id, @PathVariable Long messageId) {
        return ok(conversationService.pinMessage(id, messageId));
    }

    @RequirePermission(PermissionCodes.IM_CONVERSATION_UPDATE)
    @DeleteMapping("/{id}/pinned-message")
    public ResponseEntity<?> unpinMessage(@PathVariable Long id) {
        return ok(conversationService.unpinMessage(id));
    }

    @AuditLog(action = "UPDATE_GATE_CONFIG", resourceType = "ImConversation", resourceId = "#id")
    @RequirePermission(PermissionCodes.IM_MEMBER_MANAGE)
    @PatchMapping("/{id}/gate-config")
    public ResponseEntity<?> updateGateConfig(@PathVariable Long id,
                                              @RequestBody ImGateConfig config) {
        return ok(conversationService.updateGateConfig(id, config));
    }

    @AuditLog(action = "MUTE_PARTICIPANT", resourceType = "ImConversation", resourceId = "#id")
    @RequirePermission(PermissionCodes.IM_MEMBER_MANAGE)
    @PostMapping("/{id}/participants/{participantId}/mute")
    public ResponseEntity<?> muteParticipant(@PathVariable Long id,
                                             @PathVariable Long participantId,
                                             @RequestParam(defaultValue = "true") boolean muted) {
        conversationService.updateParticipantMute(id, participantId, muted);
        return ok();
    }

    @RequirePermission(PermissionCodes.IM_CONVERSATION_READ)
    @PostMapping("/{id}/read")
    public ResponseEntity<?> read(@PathVariable Long id, @RequestBody MarkReadDTO dto) {
        conversationService.markRead(id, dto == null ? new MarkReadDTO() : dto);
        return ok();
    }
}
