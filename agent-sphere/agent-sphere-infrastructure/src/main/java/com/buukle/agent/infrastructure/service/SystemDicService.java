package com.buukle.agent.infrastructure.service;

import com.buukle.agent.infrastructure.controller.dtvo.SystemDicSaveDTO;
import com.buukle.agent.infrastructure.controller.dtvo.SystemDicVO;

import java.util.List;

public interface SystemDicService {

    List<SystemDicVO> list(String dicType, String keyword);

    SystemDicVO create(SystemDicSaveDTO dto);

    SystemDicVO update(Long id, SystemDicSaveDTO dto);

    void delete(Long id);
}
