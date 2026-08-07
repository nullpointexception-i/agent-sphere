package com.buukle.agent.completions.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.buukle.agent.completions.dtvo.CompletionsCallVO;
import com.buukle.agent.completions.dtvo.CompletionsInput;
import com.buukle.agent.completions.dtvo.CompletionsVO;
import com.buukle.agent.completions.dtvo.CreateCompletionsDTO;
import com.buukle.agent.completions.spi.CompletionsSpi;

import java.time.LocalDateTime;

public interface CompletionsService extends CompletionsSpi {
    CompletionsVO create(CreateCompletionsDTO dto);

    CompletionsVO update(Long id, CreateCompletionsDTO dto);

    void delete(Long id);

    Page<CompletionsVO> list(String keyword, LocalDateTime startTime, LocalDateTime endTime, int page, int size);

    CompletionsVO detail(Long id);

    Page<CompletionsCallVO> listCalls(Long id, int page, int size);
}
