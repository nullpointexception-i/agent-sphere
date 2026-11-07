package com.buukle.agent.runtime.orchestration.controller;


import com.buukle.agent.common.constant.ApiPrefix;
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
 * Browser extension connection management: when multiple devices are online, the user chooses which one executes.
 *
 * <p>Routing itself happens in {@code SseEventRelay}: if the preferred device is online, deliver directly to it;
 * if it is offline, fall back to the newest connection; if several are online and none is selected, require a selection first.
 */
@RestController
@RequestMapping(ApiPrefix.V1 + "/runtime/user/connections")
@RequiredArgsConstructor
public class UserConnectionController extends BaseController {

    private final UserConnectionRegistry userConnectionRegistry;

    /** Online device list; current marks the device that issued this request. */
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

    /** Select the executing device (by deviceId, preserved across reconnects). */
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
