package com.buukle.agent.instance.spi;

import com.buukle.agent.instance.dtvo.vo.IssuedTokenVO;
import com.buukle.agent.instance.dtvo.vo.UserSessionVO;

import java.util.List;

/**
 * 登录会话契约：签发、校验、吊销。
 *
 * <p>一个用户可拥有多条有效会话；任何一端登录都不会覆盖其它端。
 */
public interface UserTokenSpi {

    /** 签发一条新会话，返回 token 明文与过期时间。 */
    IssuedTokenVO issue(Long userId, String source, String loginIp, String userAgent);

    /** 校验 token，有效返回 userId；同时按 7 天节流做滑动续期。 */
    Long validate(String rawToken);

    /** 吊销单个 token（登出）。 */
    void revoke(String rawToken);

    /** 吊销某用户全部有效 token（退出所有设备）。 */
    int revokeAll(Long userId);

    /** 吊销某用户除指定 token 外的全部会话（改密后保留当前设备）。 */
    int revokeAllExcept(Long userId, String keepRawToken);

    /** 吊销某用户的一条会话（按 id，必须属于该用户）。 */
    void revokeById(Long userId, Long sessionId);

    /** 列出某用户的有效会话；currentTokenHash 用于标记当前会话，可为 null。 */
    List<UserSessionVO> listActive(Long userId, String currentTokenHash);

    /** 按 token hash 反查来源端（CONSOLE/WIDGET）；用于浏览器连接登记，查不到返回 null。 */
    String findSourceByHash(String tokenHash);
}
