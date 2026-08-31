package com.buukle.agent.instance.controller;

import com.buukle.agent.capability.builtin.dtvo.vo.BuiltinToolVO;
import com.buukle.agent.capability.builtin.spi.CapabilityBuiltinSpi;
import com.buukle.agent.capability.cli.dtvo.vo.CliVO;
import com.buukle.agent.capability.cli.spi.CapabilityCliSpi;
import com.buukle.agent.capability.mcp.dtvo.vo.McpVO;
import com.buukle.agent.capability.mcp.spi.CapabilityMcpSpi;
import com.buukle.agent.capability.skill.dtvo.vo.SkillVO;
import com.buukle.agent.capability.skill.spi.CapabilitySkillSpi;
import com.buukle.agent.common.context.WithTenant;
import com.buukle.agent.common.annotation.RequirePermission;
import com.buukle.agent.common.util.BaseController;
import com.buukle.agent.instance.dtvo.dto.BatchCreateInstanceCapabilityDTO;
import com.buukle.agent.instance.dtvo.dto.CreateInstanceCapabilityDTO;
import com.buukle.agent.instance.dtvo.vo.CapabilityFullVO;
import com.buukle.agent.instance.dtvo.vo.CapabilityVO;
import com.buukle.agent.instance.service.InstanceCapabilityService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/v1/instance/instance-capabilities")
@RequiredArgsConstructor
@WithTenant
public class InstanceCapabilityController extends BaseController {
    private final InstanceCapabilityService instanceCapabilityService;
    private final CapabilityMcpSpi capabilityMcpSpi;
    private final CapabilitySkillSpi capabilitySkillSpi;
    private final CapabilityCliSpi capabilityCliSpi;
    private final CapabilityBuiltinSpi capabilityBuiltinSpi;

    @GetMapping
    public ResponseEntity<?> list(@RequestParam Long instanceId) {
        return ok(instanceCapabilityService.getCapabilitiesByInstance(instanceId));
    }

    @RequirePermission("instance:capability:bind")
    @PostMapping
    public ResponseEntity<?> create(@Valid @RequestBody CreateInstanceCapabilityDTO dto) {
        return created(instanceCapabilityService.createCapability(dto));
    }

    @RequirePermission("instance:capability:bind")
    @PostMapping("/batch")
    public ResponseEntity<?> batchCreate(@Valid @RequestBody BatchCreateInstanceCapabilityDTO dto) {
        return created(instanceCapabilityService.batchCreateCapabilities(dto));
    }

    @RequirePermission("instance:capability:unbind")
    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(@PathVariable Long id) {
        instanceCapabilityService.deleteCapability(id);
        return ok();
    }

    @GetMapping("/full")
    public ResponseEntity<?> listFull(@RequestParam Long instanceId) {
        List<CapabilityVO> caps = instanceCapabilityService.getCapabilitiesByInstance(instanceId);
        List<Long> mcpIds = new ArrayList<>();
        List<Long> skillIds = new ArrayList<>();
        List<Long> cliIds = new ArrayList<>();
        for (CapabilityVO c : caps) {
            switch (c.getCapabilityType()) {
                case "mcp" -> mcpIds.add(c.getCapabilityId());
                case "skill" -> skillIds.add(c.getCapabilityId());
                case "cli" -> cliIds.add(c.getCapabilityId());
            }
        }
        Map<Long, String> mcpNames = capabilityMcpSpi.listMcpsByIds(mcpIds).stream().collect(Collectors.toMap(McpVO::getId, McpVO::getName));
        Map<Long, SkillVO> skillById = capabilitySkillSpi.listSkillsByIds(skillIds).stream()
                .collect(Collectors.toMap(SkillVO::getId, Function.identity()));
        Map<Long, String> skillNames = skillById.entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, e -> e.getValue().getName()));
        Map<Long, String> cliNames = capabilityCliSpi.listClisByIds(cliIds).stream().collect(Collectors.toMap(CliVO::getId, CliVO::getName));
        Map<Long, BuiltinToolVO> builtinMap = capabilityBuiltinSpi.listBuiltinTools().stream().collect(Collectors.toMap(BuiltinToolVO::getId, Function.identity()));
        List<CapabilityFullVO> result = new ArrayList<>();
        for (CapabilityVO c : caps) {
            CapabilityFullVO vo = new CapabilityFullVO();
            vo.setId(c.getId());
            vo.setCapabilityType(c.getCapabilityType());
            vo.setCapabilityId(c.getCapabilityId());
            vo.setStatus(c.getStatus());
            String name = switch (c.getCapabilityType()) {
                case "mcp" -> mcpNames.get(c.getCapabilityId());
                case "skill" -> skillNames.get(c.getCapabilityId());
                case "cli" -> cliNames.get(c.getCapabilityId());
                case "builtin" -> {
                    BuiltinToolVO b = builtinMap.get(c.getCapabilityId());
                    if (b != null) {
                        vo.setDisplayNameCn(b.getDisplayNameCn());
                        vo.setDisplayNameEn(b.getDisplayNameEn());
                    }
                    yield b != null ? b.getName() : null;
                }
                default -> null;
            };
            vo.setName(name);
            if ("skill".equals(c.getCapabilityType())) {
                SkillVO skill = skillById.get(c.getCapabilityId());
                if (skill != null) {
                    vo.setDescription(skill.getDescription());
                    vo.setStatus(skill.getStatus());
                }
            }
            result.add(vo);
        }
        return ok(result);
    }
}
