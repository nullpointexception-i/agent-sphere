package com.buukle.agent.instance.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.buukle.agent.common.error.CommonErrorCode;
import com.buukle.agent.common.exception.BizException;
import com.buukle.agent.instance.domain.AgentChatShare;
import com.buukle.agent.instance.domain.AgentRun;
import com.buukle.agent.instance.dtvo.vo.ChatShareVO;
import com.buukle.agent.instance.dtvo.vo.SharedChatVO;
import com.buukle.agent.instance.dtvo.vo.SessionVO;
import com.buukle.agent.instance.repository.AgentChatShareMapper;
import com.buukle.agent.instance.repository.RunMapper;
import com.buukle.agent.instance.spi.SessionSpi;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ChatShareService {

    private static final int RUN_PAGE_SIZE = 3;
    private static final String STATUS_ACTIVE = "ACTIVE";

    private final AgentChatShareMapper shareMapper;
    private final RunMapper runMapper;
    private final SessionSpi sessionSpi;

    public ChatShareVO create(Long sessionId, Long runId, Integer runCount, String username) {
        SessionVO session = sessionSpi.getSession(sessionId);
        if (session == null) {
            throw new BizException(CommonErrorCode.PARAM_INVALID, "Session not found");
        }
        if (username != null && !username.isBlank() && !username.equals(session.getCreatedBy())) {
            throw new BizException(CommonErrorCode.FORBIDDEN);
        }
        AgentRun anchor = runMapper.selectById(runId);
        if (anchor == null || !sessionId.equals(anchor.getSessionId())) {
            throw new BizException(CommonErrorCode.PARAM_INVALID, "Run not found");
        }
        AgentChatShare share = new AgentChatShare();
        share.setShareToken(UUID.randomUUID().toString().replace("-", ""));
        share.setSessionId(sessionId);
        share.setAnchorRunId(runId);
        share.setRunCount(runCount == null ? 3 : Math.max(1, Math.min(runCount, 20)));
        share.setCreatedBy(username);
        share.setStatus(STATUS_ACTIVE);
        shareMapper.insert(share);
        ChatShareVO vo = new ChatShareVO();
        vo.setShareToken(share.getShareToken());
        return vo;
    }

    public SharedChatVO getShared(String token) {
        AgentChatShare share = shareMapper.selectOne(new LambdaQueryWrapper<AgentChatShare>()
                .eq(AgentChatShare::getShareToken, token)
                .eq(AgentChatShare::getStatus, STATUS_ACTIVE)
                .last("LIMIT 1"));
        if (share == null) {
            throw new BizException(CommonErrorCode.PARAM_INVALID, "Share not found");
        }
        int limit = share.getRunCount() == null ? RUN_PAGE_SIZE : Math.max(1, share.getRunCount());
        List<AgentRun> rows = runMapper.selectList(new LambdaQueryWrapper<AgentRun>()
                .eq(AgentRun::getSessionId, share.getSessionId())
                .le(AgentRun::getId, share.getAnchorRunId())
                .orderByDesc(AgentRun::getId)
                .last("LIMIT " + limit));
        rows = new ArrayList<>(rows);
        Collections.reverse(rows);
        List<SharedChatVO.SharedRunVO> runs = new ArrayList<>();
        for (AgentRun run : rows) {
            SharedChatVO.SharedRunVO vo = new SharedChatVO.SharedRunVO();
            vo.setRunId(run.getId());
            vo.setUserMessage(run.getUserMessage());
            vo.setAssistantReply(run.getAssistantReply());
            vo.setCreatedAt(run.getCreatedAt() != null ? run.getCreatedAt().toString() : null);
            runs.add(vo);
        }
        SessionVO session = sessionSpi.getSession(share.getSessionId());
        SharedChatVO vo = new SharedChatVO();
        vo.setTitle(session != null ? session.getTitle() : null);
        vo.setRuns(runs);
        vo.setHasMore(false);
        vo.setNextBeforeRunId(null);
        return vo;
    }
}
