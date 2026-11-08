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
 * Routing rules: preferred device online → use it; offline → fall back to the newest; multiple online and none selected → require a selection first.
 *
 * <p>This rule determines whether browser commands run on the wrong machine, so it must be locked down.
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
        lenient().when(connections.entrySet()).thenAnswer(inv -> Map.copyOf(stored).entrySet());
        lenient().when(connections.put(anyString(), anyString())).thenAnswer(inv -> {
            stored.put(inv.getArgument(0), inv.getArgument(1));
            return null;
        });
        lenient().when(connections.remove(anyString())).thenAnswer(inv -> stored.remove(inv.getArgument(0)));
    }

    private void addConnection(String connectionId, String deviceId, long connectedAt) {
        UserConnectionRegistry.ConnectionView view = new UserConnectionRegistry.ConnectionView(
                connectionId, deviceId, "Chrome · " + deviceId, "WIDGET", "1.0.171", connectedAt,
                System.currentTimeMillis());
        stored.put(connectionId, toJson(view));
    }

    private static String toJson(UserConnectionRegistry.ConnectionView view) {
        return String.format("{\"connectionId\":\"%s\",\"deviceId\":%s,\"deviceName\":\"%s\","
                        + "\"source\":\"%s\",\"extensionVersion\":%s,\"connectedAt\":%d,\"lastSeen\":%d}",
                view.connectionId(),
                view.deviceId() == null ? "null" : "\"" + view.deviceId() + "\"",
                view.deviceName(), view.source(),
                view.extensionVersion() == null ? "null" : "\"" + view.extensionVersion() + "\"",
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
    void connectionsWithoutDeviceIdFallBackToLatest() {
        // Older extensions do not report deviceId: the user cannot choose, so deliver to the newest connection (same as before)
        addConnection("c1", null, 1000L);
        addConnection("c2", null, 2000L);

        UserConnectionRegistry.Selection selection = registry.pick(USERNAME);

        assertEquals(UserConnectionRegistry.SelectionState.OK, selection.state());
        assertEquals("c2", selection.target().connectionId());
    }

    @Test
    void duplicateConnectionsOfSameDeviceKeepOnlyNewest() {
        addConnection("c-old", "d1", 1000L);
        addConnection("c-new", "d1", 2000L);

        List<UserConnectionRegistry.ConnectionView> live = registry.list(USERNAME);

        assertEquals(1, live.size(), "同一设备只应展示一行");
        assertEquals("c-new", live.get(0).connectionId());
    }

    @Test
    void registerWithSameDevicePrunesOlderConnection() {
        registry.register(USERNAME, "c-old", "d1", "Chrome", "WIDGET", "1.0.160", 1000L);
        registry.register(USERNAME, "c-new", "d1", "Chrome", "WIDGET", "1.0.171", 2000L);

        assertEquals(1, stored.size());
        assertEquals("c-new", registry.list(USERNAME).get(0).connectionId());
        assertEquals("1.0.171", registry.list(USERNAME).get(0).extensionVersion());
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
