package com.buukle.agent.common.chrome;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RBucket;
import org.redisson.api.RMap;
import org.redisson.api.RedissonClient;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * 选路规则：首选设备在线 → 用它；离线 → 回退最新；多台在线且未选择 → 要求先选。
 *
 * <p>这条规则决定了浏览器指令会不会跑错机器，必须锁死。
 */
@ExtendWith(MockitoExtension.class)
class UserConnectionRegistryTest {

    private static final String USERNAME = "bole_8";

    @Mock
    RedissonClient redissonClient;
    @Mock
    RMap<String, String> connections;
    @Mock
    RBucket<String> preferredBucket;

    private UserConnectionRegistry registry;
    private final Map<String, String> stored = new ConcurrentHashMap<>();

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        registry = new UserConnectionRegistry(redissonClient);
        lenient().when(redissonClient.<String, String>getMap(anyString())).thenReturn(connections);
        lenient().when(redissonClient.<String>getBucket(anyString())).thenReturn(preferredBucket);
        lenient().when(connections.readAllValues()).thenAnswer(inv -> (Collection<String>) List.copyOf(stored.values()));
        lenient().when(connections.put(anyString(), anyString())).thenAnswer(inv -> {
            stored.put(inv.getArgument(0), inv.getArgument(1));
            return null;
        });
        lenient().when(connections.remove(anyString())).thenAnswer(inv -> stored.remove(inv.getArgument(0)));
    }

    private void addConnection(String connectionId, String deviceId, long connectedAt) {
        UserConnectionRegistry.ConnectionView view = new UserConnectionRegistry.ConnectionView(
                connectionId, deviceId, "Chrome · " + deviceId, "WIDGET", connectedAt,
                System.currentTimeMillis());
        stored.put(connectionId, toJson(view));
    }

    private static String toJson(UserConnectionRegistry.ConnectionView view) {
        return String.format("{\"connectionId\":\"%s\",\"deviceId\":\"%s\",\"deviceName\":\"%s\","
                        + "\"source\":\"%s\",\"connectedAt\":%d,\"lastSeen\":%d}",
                view.connectionId(), view.deviceId(), view.deviceName(), view.source(),
                view.connectedAt(), view.lastSeen());
    }

    @Test
    void emptyWhenNoConnection() {
        assertEquals(UserConnectionRegistry.SelectionState.EMPTY, registry.pick(USERNAME).state());
    }

    @Test
    void singleConnectionIsUsedWithoutAskingUser() {
        addConnection("c1", "d1", 1000L);

        UserConnectionRegistry.Selection selection = registry.pick(USERNAME);

        assertEquals(UserConnectionRegistry.SelectionState.OK, selection.state());
        assertEquals("c1", selection.target().connectionId());
    }

    @Test
    void multipleConnectionsWithoutPreferenceRequireSelection() {
        addConnection("c1", "d1", 1000L);
        addConnection("c2", "d2", 2000L);

        assertEquals(UserConnectionRegistry.SelectionState.NEEDS_SELECTION, registry.pick(USERNAME).state());
    }

    @Test
    void preferredDeviceWins() {
        addConnection("c1", "d1", 1000L);
        addConnection("c2", "d2", 2000L);
        when(preferredBucket.get()).thenReturn("d1");

        UserConnectionRegistry.Selection selection = registry.pick(USERNAME);

        assertEquals(UserConnectionRegistry.SelectionState.OK, selection.state());
        assertEquals("c1", selection.target().connectionId());
    }

    @Test
    void offlinePreferredDeviceFallsBackToLatest() {
        addConnection("c1", "d1", 1000L);
        addConnection("c2", "d2", 2000L);
        when(preferredBucket.get()).thenReturn("missing");

        UserConnectionRegistry.Selection selection = registry.pick(USERNAME);

        assertEquals(UserConnectionRegistry.SelectionState.DEGRADED, selection.state());
        assertEquals("c2", selection.target().connectionId(), "应回退到最新连接");
    }

    @Test
    void staleConnectionsAreIgnored() {
        long stale = System.currentTimeMillis() - UserConnectionRegistry.STALE_MILLIS - 5_000L;
        stored.put("c-old", String.format("{\"connectionId\":\"c-old\",\"deviceId\":\"d-old\","
                        + "\"deviceName\":\"old\",\"source\":\"WIDGET\",\"connectedAt\":1,\"lastSeen\":%d}", stale));

        assertEquals(UserConnectionRegistry.SelectionState.EMPTY, registry.pick(USERNAME).state());
        assertNull(registry.pick(USERNAME).target());
    }
}
