package com.buukle.agent.instance.service.impl;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.buukle.agent.common.exception.BizException;
import com.buukle.agent.instance.domain.AgentRun;
import com.buukle.agent.instance.dtvo.dto.CreateRunDTO;
import com.buukle.agent.instance.dtvo.vo.MessageHistoryVO;
import com.buukle.agent.instance.dtvo.vo.RunVO;
import com.buukle.agent.instance.exception.InstanceErrorCode;
import com.buukle.agent.instance.repository.RunMapper;
import com.buukle.agent.instance.service.RunService;
import com.buukle.agent.instance.service.converter.RunConverter;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class RunServiceImpl extends ServiceImpl<RunMapper, AgentRun> implements RunService {
    private final RunConverter runConverter;

    @Override
    public RunVO createRun(CreateRunDTO dto) {
        AgentRun run = runConverter.toDO(dto);
        save(run);
        return runConverter.toVO(run);
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
        return p.convert(runConverter::toVO);
    }

    @Override
    public MessageHistoryVO getMessageHistory(Long sessionId, String direction, Long runId) {
        var query = lambdaQuery()
                .eq(AgentRun::getSessionId, sessionId)
                .ne(AgentRun::getUserMessage, "")
                .select(AgentRun::getId, AgentRun::getUserMessage);

        if (runId != null) {
            if ("prev".equals(direction)) {
                query.lt(AgentRun::getId, runId).orderByDesc(AgentRun::getId);
            } else {
                query.gt(AgentRun::getId, runId).orderByAsc(AgentRun::getId);
            }
        } else {
            if ("next".equals(direction)) {
                return new MessageHistoryVO(null, null, false);
            }
            query.orderByDesc(AgentRun::getId);
        }

        AgentRun run = query.last("LIMIT 1").one();
        if (run == null) return new MessageHistoryVO(null, null, false);

        boolean hasMore;
        if ("prev".equals(direction) || runId == null) {
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
