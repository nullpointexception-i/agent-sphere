package com.buukle.agent.runtime.orchestration.assembler;

import com.buukle.agent.instance.dtvo.vo.InstanceVO;
import com.buukle.agent.instance.dtvo.vo.RunVO;
import com.buukle.agent.instance.dtvo.vo.SessionVO;
import com.buukle.agent.runtime.kernel.port.KernelContext;
import org.springframework.stereotype.Component;

@Component
public class KernelContextAssembler {
    public KernelContext assemble(InstanceVO agentInstance, SessionVO session, RunVO run, String userMessage) {
        return KernelContext.builder()
                .agentInstance(agentInstance)
                .session(session)
                .run(run)
                .userMessage(userMessage)
                .build();
    }
}
