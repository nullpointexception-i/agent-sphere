package com.buukle.agent.runtime.kernel.runner;

import com.buukle.agent.instance.domain.AgentRun;
import com.buukle.agent.instance.domain.AgentSession;
import com.buukle.agent.instance.dtvo.dto.CreateRunDTO;
import com.buukle.agent.instance.dtvo.vo.RunVO;
import com.buukle.agent.instance.service.converter.RunConverter;
import com.buukle.agent.instance.service.converter.SessionConverter;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RunModeConverterTest {

    @Test
    void sessionConverterCarriesRunMode() {
        AgentSession session = new AgentSession();
        session.setRunMode("READ_ONLY");
        assertEquals("READ_ONLY", new SessionConverter().toVO(session).getRunMode());
    }

    @Test
    void createRunCarriesRunMode() {
        CreateRunDTO dto = new CreateRunDTO();
        dto.setSessionId(1L);
        dto.setType("auto");
        dto.setUserMessage("hi");
        dto.setRunMode("READ_ONLY");
        AgentRun run = new RunConverter().toDO(dto);
        assertEquals("READ_ONLY", run.getRunMode());
    }

    @Test
    void runConverterCarriesRunMode() {
        AgentRun run = new AgentRun();
        run.setRunMode("READ_ONLY");
        assertEquals("READ_ONLY", new RunConverter().toVO(run).getRunMode());
    }
}
