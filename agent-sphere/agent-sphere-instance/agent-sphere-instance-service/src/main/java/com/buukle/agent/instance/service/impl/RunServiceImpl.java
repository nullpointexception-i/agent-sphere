package com.buukle.agent.instance.service.impl;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.buukle.agent.common.exception.BizException;
import com.buukle.agent.instance.domain.AgentRun;
import com.buukle.agent.instance.dtvo.dto.CreateRunDTO;
import com.buukle.agent.instance.dtvo.enums.RunEnum;
import com.buukle.agent.instance.dtvo.vo.MessageHistoryVO;
import com.buukle.agent.instance.dtvo.vo.RunVO;
import com.buukle.agent.instance.exception.InstanceErrorCode;
import com.buukle.agent.instance.repository.RunMapper;
import com.buukle.agent.instance.dtvo.vo.ClarificationVO;
import com.buukle.agent.instance.service.ClarificationService;
import com.buukle.agent.instance.service.RunService;
import com.buukle.agent.instance.service.converter.RunConverter;
import com.buukle.agent.runtime.kernel.constants.ChatClarification;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class RunServiceImpl extends ServiceImpl<RunMapper, AgentRun> implements RunService {
    private final RunConverter runConverter;
    private final ClarificationService clarificationService;

    @Override
    public RunVO createRun(CreateRunDTO dto) {
        AgentRun run = runConverter.toDO(dto);
        save(run);
        RunVO vo = runConverter.toVO(run);
        vo.setNoClarification(dto.getNoClarification());
        return vo;
    }

    @Override
    public RunVO getRun(Long id) {
        AgentRun run = getById(id);
        if (run == null) throw new BizException(InstanceErrorCode.RUN_NOT_FOUND);
        return runConverter.toVO(run);
    }

    @Override
    public void updateRun(RunVO run) {
        AgentRun entity = runConverter.toDO(run);
        updateById(entity);
    }

    @Override
    public List<RunVO> listRunsBySessionAfterId(Long sessionId, Long afterRunId) {
        List<AgentRun> runs = lambdaQuery().eq(AgentRun::getSessionId, sessionId)
                .gt(AgentRun::getId, afterRunId)
                .orderByAsc(AgentRun::getId)
                .list();
        return runs.stream().map(runConverter::toVO).toList();
    }

    @Override
    public IPage<RunVO> pageRunsBySession(Long sessionId, String keyword, int page, int size) {
        Page<AgentRun> p = lambdaQuery()
                .eq(AgentRun::getSessionId, sessionId)
                .like(keyword != null && !keyword.isBlank(), AgentRun::getUserMessage, keyword)
                .orderByDesc(AgentRun::getCreatedAt)
                .page(new Page<>(page, size));
        IPage<RunVO> voPage = p.convert(runConverter::toVO);
        // Attach clarification data to each run; 澄清应答 run 的 userMessage 携带前缀，展示层去掉
        List<Long> runIds = voPage.getRecords().stream().map(RunVO::getId).collect(Collectors.toList());
        if (!runIds.isEmpty()) {
            Map<Long, List<ClarificationVO>> clarificationMap = clarificationService.mapByRunIdList(runIds);
            for (RunVO runVO : voPage.getRecords()) {
                stripClarificationPrefix(runVO);
                List<ClarificationVO> cvs = clarificationMap.get(runVO.getId());
                if (cvs != null && !cvs.isEmpty()) runVO.setClarifications(cvs);
            }
        }
        return voPage;
    }

    private static void stripClarificationPrefix(RunVO runVO) {
        String userMessage = runVO.getUserMessage();
        if (userMessage != null && userMessage.startsWith(ChatClarification.CLARIFICATION_RESUME_PREFIX)) {
            runVO.setClarificationResponse(true);
            runVO.setUserMessage(userMessage.substring(ChatClarification.CLARIFICATION_RESUME_PREFIX.length()));
        }
    }

    @Override
    public MessageHistoryVO getMessageHistory(Long sessionId, String direction, Long runId) {
        var query = lambdaQuery()
                .eq(AgentRun::getSessionId, sessionId)
                .ne(AgentRun::getUserMessage, "")
                .select(AgentRun::getId, AgentRun::getUserMessage);

        if (runId != null) {
            if (RunEnum.DIRECTION_PREV.equals(direction)) {
                query.lt(AgentRun::getId, runId).orderByDesc(AgentRun::getId);
            } else {
                query.gt(AgentRun::getId, runId).orderByAsc(AgentRun::getId);
            }
        } else {
            if (RunEnum.DIRECTION_NEXT.equals(direction)) {
                return new MessageHistoryVO(null, null, false);
            }
            query.orderByDesc(AgentRun::getId);
        }

        AgentRun run = query.last("LIMIT 1").one();
        if (run == null) return new MessageHistoryVO(null, null, false);

        boolean hasMore;
        if (RunEnum.DIRECTION_PREV.equals(direction) || runId == null) {
            hasMore = lambdaQuery()
                    .eq(AgentRun::getSessionId, sessionId)
                    .ne(AgentRun::getUserMessage, "")
                    .lt(AgentRun::getId, run.getId())
                    .count() > 0;
        } else {
            hasMore = lambdaQuery()
                    .eq(AgentRun::getSessionId, sessionId)
                    .ne(AgentRun::getUserMessage, "")
                    .gt(AgentRun::getId, run.getId())
                    .count() > 0;
        }

        return new MessageHistoryVO(run.getId(), run.getUserMessage(), hasMore);
    }
}
