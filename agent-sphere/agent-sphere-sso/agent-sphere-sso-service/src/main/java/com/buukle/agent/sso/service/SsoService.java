package com.buukle.agent.sso.service;

import com.buukle.agent.instance.dtvo.vo.UserVO;
import com.buukle.agent.sso.dtvo.SsoAuthorizeVO;

public interface SsoService {

    SsoAuthorizeVO authorize(String provider, String redirectUri, String prompt);

    String callback(String code, String state, String iss, String error);

    UserVO exchange(String otc);
}