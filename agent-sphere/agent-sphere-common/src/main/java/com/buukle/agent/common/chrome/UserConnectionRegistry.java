package com.buukle.agent.common.chrome;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RBucket;
import org.redisson.api.RMap;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 用户级浏览器连接登记表（在线状态）。
 *
 * <p>只存**临时在线状态**，不落库：连接由 SSE 维系，心跳 15s 刷新 {@code lastSeen}，
 * 超过 {@link #STALE_MILLIS} 即视为掉线。Redis 重启/淘汰后由心跳重写自动重建（≤15s）。
 *
 * <p>为什么要跨副本：连接可能建立在任意 pod 上，而指令由任意 pod 发起，
 * 进程内 Map 在 replicas=2 下会随机失效。
 */
@Slf4j
@Component
public class UserConnectionRegistry {

    /** 心跳间隔 15s，2 个周期内的 lastSeen 视为存活。 */
    public static final long STALE_MILLIS = 45_000L;
    /** 登记表兜底 TTL：用户长期不来时自动清空，避免 key 堆积。 */
    private static final Duration KEY_TTL = Duration.ofHours(1);

    private static final String CONN_KEY_PREFIX = "sse:conns:";
    private static final String PREFERRED_KEY_PREFIX = "sse:preferred:";

    /** common 不依赖 agent-sphere-util（方向相反），这里自带一个最小 mapper。 */
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private final RedissonClient redissonClient;

    public UserConnectionRegistry(RedissonClient redissonClient) {
        this.redissonClient = redissonClient;
    }

    /** 一条在线连接。{@code connectedAt} 由建连时确定，心跳重写时沿用（Redis 重启后排序不乱）。 */
    public record ConnectionView(String connectionId, String deviceId, String deviceName,
                                 String source, long connectedAt, long lastSeen) {
    }

    /** 选路结果。 */
    public enum SelectionState {
        /** 有可用连接，正常投递。 */
        OK,
        /** 首选设备离线，已回退到最新连接。 */
        DEGRADED,
        /** 没有任何在线连接。 */
        EMPTY,
        /** 多台在线但用户尚未选择。 */
        NEEDS_SELECTION
    }

    public record Selection(SelectionState state, ConnectionView target,
                            List<ConnectionView> live, String preferredDeviceId) {
    }

    /** 建连登记。 */
    public void register(String username, String connectionId, String deviceId, String deviceName,
                         String source, long connectedAt) {
        if (username == null || connectionId == null) {
            return;
        }
        write(username, new ConnectionView(connectionId, deviceId, deviceName, source,
                connectedAt, System.currentTimeMillis()));
    }

    /** 心跳续期 + **重写整条记录**（Redis 重启/被淘汰后借此自愈）。 */
    public void touch(String username, ConnectionView connection) {
        if (username == null || connection == null) {
            return;
        }
        write(username, new ConnectionView(connection.connectionId(), connection.deviceId(),
                connection.deviceName(), connection.source(), connection.connectedAt(),
                System.currentTimeMillis()));
    }

    public void unregister(String username, String connectionId) {
        if (username == null || connectionId == null) {
            return;
        }
        try {
            RMap<String, String> map = connections(username);
            map.remove(connectionId);
            if (map.isEmpty()) {
                map.delete();
            }
        } catch (Exception e) {
            log.warn("Unregister browser connection failed: username={}, conn={}", username, connectionId, e);
        }
    }

    /** 存活连接列表，按建连时间倒序（最新在前）。 */
    public List<ConnectionView> list(String username) {
        if (username == null) {
            return List.of();
        }
        try {
            long cutoff = System.currentTimeMillis() - STALE_MILLIS;
            List<ConnectionView> live = new ArrayList<>();
            for (String json : connections(username).readAllValues()) {
                ConnectionView view = parse(json);
                if (view != null && view.lastSeen() >= cutoff) {
                    live.add(view);
                }
            }
            live.sort(Comparator.comparingLong(ConnectionView::connectedAt).reversed());
            return live;
        } catch (Exception e) {
            log.warn("List browser connections failed: username={}", username, e);
            return List.of();
        }
    }

    public String preferred(String username) {
        if (username == null) {
            return null;
        }
        try {
            RBucket<String> bucket = preferredBucket(username);
            String value = bucket.get();
            if (value != null) {
                bucket.expire(KEY_TTL);
            }
            return value;
        } catch (Exception e) {
            log.warn("Read preferred browser device failed: username={}", username, e);
            return null;
        }
    }

    public void setPreferred(String username, String deviceId) {
        if (username == null || deviceId == null || deviceId.isBlank()) {
            return;
        }
        try {
            preferredBucket(username).set(deviceId, KEY_TTL);
        } catch (Exception e) {
            log.warn("Save preferred browser device failed: username={}, device={}", username, deviceId, e);
        }
    }

    /**
     * 选路：首选设备在线就用它；否则回退到最新连接；多台在线且从未选择 → 要求用户先选。
     *
     * <p>只有一台连接时不打扰用户，直接投递。
     */
    public Selection pick(String username) {
        List<ConnectionView> live = list(username);
        String preferredDeviceId = preferred(username);
        if (live.isEmpty()) {
            return new Selection(SelectionState.EMPTY, null, live, preferredDeviceId);
        }
        if (live.size() == 1) {
            return new Selection(SelectionState.OK, live.get(0), live, preferredDeviceId);
        }
        if (preferredDeviceId != null) {
            for (ConnectionView view : live) {
                if (preferredDeviceId.equals(view.deviceId())) {
                    return new Selection(SelectionState.OK, view, live, preferredDeviceId);
                }
            }
            // 首选设备离线：回退到最新连接，并标记降级，便于上层提示
            return new Selection(SelectionState.DEGRADED, live.get(0), live, preferredDeviceId);
        }
        return new Selection(SelectionState.NEEDS_SELECTION, null, live, null);
    }

    private void write(String username, ConnectionView view) {
        try {
            RMap<String, String> map = connections(username);
            map.put(view.connectionId(), MAPPER.writeValueAsString(view));
            map.expire(KEY_TTL);
        } catch (Exception e) {
            log.warn("Write browser connection failed: username={}, device={}", username, view.deviceId(), e);
        }
    }

    private RMap<String, String> connections(String username) {
        return redissonClient.getMap(CONN_KEY_PREFIX + username);
    }

    private RBucket<String> preferredBucket(String username) {
        return redissonClient.getBucket(PREFERRED_KEY_PREFIX + username);
    }

    private static ConnectionView parse(String json) {
        try {
            return MAPPER.readValue(json, ConnectionView.class);
        } catch (Exception e) {
            return null;
        }
    }
}
