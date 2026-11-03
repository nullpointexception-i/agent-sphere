package com.buukle.agent.runtime.orchestration.controller;

import com.buukle.agent.common.chrome.UserConnectionRegistry;
import com.buukle.agent.common.context.AuthContext;
import com.buukle.agent.common.error.CommonErrorCode;
import com.buukle.agent.common.exception.BizException;
import com.buukle.agent.common.util.BaseController;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 浏览器插件连接管理：多台设备在线时由用户选择「用哪台执行」。
 *
 * <p>选路本身在 {@code SseEventRelay} 里完成：首选设备在线则定向投递，
 * 离线回退到最新连接，多台在线且未选择则要求先选。
 */
@RestController
@RequestMapping("/api/v1/runtime/user/connections")
@RequiredArgsConstructor
public class UserConnectionController extends BaseController {

    private final UserConnectionRegistry userConnectionRegistry;

    /** 在线设备列表；current 标记发起本次请求的设备。 */
    @GetMapping
    public ResponseEntity<ConnectionsVO> list(
            @RequestHeader(value = SseRuntimeController.HEADER_DEVICE_ID, required = false) String deviceId) {
        String username = requireUsername();
        List<UserConnectionRegistry.ConnectionView> live = userConnectionRegistry.list(username);
        List<DeviceView> devices = live.stream()
                .map(v -> new DeviceView(v.deviceId(), v.deviceName(), v.source(), v.connectedAt(),
                        v.deviceId() != null && v.deviceId().equals(deviceId)))
                .toList();
        return ok(new ConnectionsVO(userConnectionRegistry.preferred(username), devices));
    }

    /** 选定执行设备（按 deviceId，跨重连保持）。 */
    @PutMapping("/preferred")
    public ResponseEntity<?> setPreferred(@RequestBody PreferredDeviceDTO dto) {
        String username = requireUsername();
        if (dto == null || dto.deviceId() == null || dto.deviceId().isBlank()) {
            throw new BizException(CommonErrorCode.PARAM_INVALID, "deviceId is required");
        }
        userConnectionRegistry.setPreferred(username, dto.deviceId());
        return ok();
    }

    private static String requireUsername() {
        String username = AuthContext.getUsername();
        if (username == null || username.isBlank()) {
            throw new BizException(CommonErrorCode.UNAUTHORIZED);
        }
        return username;
    }

    public record DeviceView(String deviceId, String deviceName, String source, long connectedAt, boolean current) {
    }

    public record ConnectionsVO(String preferredDeviceId, List<DeviceView> devices) {
    }

    public record PreferredDeviceDTO(String deviceId) {
    }
}
