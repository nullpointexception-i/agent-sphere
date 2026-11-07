package com.buukle.agent.superagent.controller;



import com.buukle.agent.common.constant.ApiPrefix;
import com.buukle.agent.common.constant.PermissionCodes;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.buukle.agent.common.annotation.RequirePermission;
import com.buukle.agent.common.context.WithTenant;
import com.buukle.agent.common.util.BaseController;
import com.buukle.agent.superagent.domain.AgentWatchdogRun;
import com.buukle.agent.superagent.repository.AgentWatchdogRunMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(ApiPrefix.V1 + "/super-agents/{superAgentId}/watchdog-runs")
@WithTenant
@RequiredArgsConstructor
public class SuperAgentWatchdogController extends BaseController {

    private static final int MAX_RUNS = 50;

    private final AgentWatchdogRunMapper runMapper;

    @RequirePermission(PermissionCodes.SUPER_AGENT_READ)
    @GetMapping
    public ResponseEntity<?> list(@PathVariable Long superAgentId) {
        return ok(runMapper.selectList(Wrappers.<AgentWatchdogRun>lambdaQuery()
                .orderByDesc(AgentWatchdogRun::getId)
                .last("LIMIT " + MAX_RUNS)));
    }
}
