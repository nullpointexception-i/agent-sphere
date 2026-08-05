package com.buukle.agent.sso.exception;

import com.buukle.agent.common.exception.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum SsoErrorCode implements ErrorCode {
    PROVIDER_NOT_FOUND("S0001", "身份源不存在", "请检查身份源标识"),
    PROVIDER_NOT_ENABLED("S0002", "身份源未启用", "请联系管理员启用身份源"),
    AUTHORIZE_INVALID("S0003", "授权请求无效", "请重新发起登录"),
    STATE_MISMATCH("S0004", "授权状态不匹配", "请重新发起登录"),
    TOKEN_EXCHANGE_FAILED("S0005", "令牌交换失败", "请稍后重试"),
    ID_TOKEN_INVALID("S0006", "身份令牌校验失败", "请重新登录"),
    OTC_INVALID("S0007", "登录凭证已失效", "请重新登录"),
    PROVISION_FAILED("S0008", "用户开通失败", "请稍后重试"),
    PROVIDER_CODE_EXISTS("S0009", "身份源标识已存在", "请更换身份源标识"),
    CONNECTION_FAILED("S0010", "连接测试失败", "请检查身份源配置");

    private final String code;
    private final String message;
    private final String userTip;
}