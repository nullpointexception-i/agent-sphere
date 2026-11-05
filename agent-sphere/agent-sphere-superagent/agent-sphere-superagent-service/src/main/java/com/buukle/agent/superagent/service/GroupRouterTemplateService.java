package com.buukle.agent.superagent.service;

import com.buukle.agent.superagent.dtvo.vo.GroupRouterTemplateVO;

/** Manages the shared Group Router prompt template. */
public interface GroupRouterTemplateService {

    GroupRouterTemplateVO get();

    GroupRouterTemplateVO update(String template);
}
