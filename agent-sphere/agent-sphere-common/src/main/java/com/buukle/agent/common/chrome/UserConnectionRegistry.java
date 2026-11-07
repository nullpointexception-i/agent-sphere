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
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * User-level browser connection registry (presence).
 *
 * <p>Holds <b>transient presence only</b>, never persisted: connections are kept alive by SSE, the
 * heartbeat refreshes {@code lastSeen} every 15s, and anything older than {@link #STALE_MILLIS}
 * counts as disconnected. After a Redis restart/eviction, heartbeat rewrites rebuild it
 * automatically (≤15s).
 *
 * <p>Why it has to span replicas: a connection may be established on any pod and commands may be
 * issued from any pod, so an in-process Map would fail randomly with replicas=2.
 */
@Slf4j
@Component
public class UserConnectionRegistry {

    /** Heartbeat interval is 15s; a lastSeen within 2 cycles counts as alive. */
    public static final long STALE_MILLIS = 45_000L;
    /** Fallback TTL for the registry: clears itself when a user stays away, preventing key buildup. */
    private static final Duration KEY_TTL = Duration.ofHours(1);

    private static final String CONN_KEY_PREFIX = "sse:conns:";
    private static final String PREFERRED_KEY_PREFIX = "sse:preferred:";

    /** common does not depend on agent-sphere-util (the dependency runs the other way), so it carries its own minimal mapper. */
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private final RedissonClient redissonClient;

    public UserConnectionRegistry(RedissonClient redissonClient) {
        this.redissonClient = redissonClient;
    }

    /** One live connection. {@code connectedAt} is fixed at connect time and preserved across heartbeat rewrites (so ordering survives a Redis restart). */
    public record ConnectionView(String connectionId, String deviceId, String deviceName,
                                 String source, long connectedAt, long lastSeen) {
    }

    /** Routing result. */
    public enum SelectionState {
        /** A usable connection exists; deliver normally. */
        OK,
        /** The preferred device is offline; fell back to the newest connection. */
        DEGRADED,
        /** No live connection at all. */
        EMPTY,
        /** Multiple devices are online but the user has not chosen one yet. */
        NEEDS_SELECTION
    }

    public record Selection(SelectionState state, ConnectionView target,
                            List<ConnectionView> live, String preferredDeviceId) {
    }

    /** Registers a connection. */
    public void register(String username, String connectionId, String deviceId, String deviceName,
                         String source, long connectedAt) {
        if (username == null || connectionId == null) {
            return;
        }
        write(username, new ConnectionView(connectionId, deviceId, deviceName, source,
                connectedAt, System.currentTimeMillis()));
        // Keep only one connection per device: an extension reconnect changes connectionId, and the
        // old connection may not have been declared disconnected yet (after a client abort the TCP
        // socket is half-open, so send does not necessarily fail immediately); without cleanup you
        // end up with "one device, multiple rows".
        if (deviceId != null && !deviceId.isBlank()) {
            pruneOtherConnectionsOfDevice(username, deviceId, connectionId);
        }
    }

    /** Heartbeat renewal + <b>rewrite the whole record</b> (this is how it self-heals after a Redis restart/eviction). */
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

    /** Live connections, newest connect time first; only the newest entry per device is kept. */
    public List<ConnectionView> list(String username) {
        if (username == null) {
            return List.of();
        }
        try {
            long cutoff = System.currentTimeMillis() - STALE_MILLIS;
            Map<String, ConnectionView> newestPerDevice = new HashMap<>();
            List<ConnectionView> unidentified = new ArrayList<>();
            for (String json : connections(username).readAllValues()) {
                ConnectionView view = parse(json);
                if (view == null || view.lastSeen() < cutoff) {
                    continue;
                }
                if (view.deviceId() == null || view.deviceId().isBlank()) {
                    // Old extension versions have no deviceId: deduplication is impossible, so keep
                    // them per connection (selection also uses the fallback path)
                    unidentified.add(view);
                    continue;
                }
                ConnectionView current = newestPerDevice.get(view.deviceId());
                if (current == null || view.connectedAt() > current.connectedAt()) {
                    newestPerDevice.put(view.deviceId(), view);
                }
            }
            List<ConnectionView> live = new ArrayList<>(newestPerDevice.values());
            live.addAll(unidentified);
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
     * Routing: use the preferred device when it is online; otherwise fall back to the newest
     * connection; multiple online and never chosen → ask the user to choose first.
     *
     * <p>With a single connection the user is not bothered; delivery happens directly.
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
        // Old extension versions do not report deviceId: the user cannot distinguish them in the
        // overlay (nor remember a choice), so fall back to "newest connection" — same behavior as
        // before, avoiding getting such clients stuck on device_selection_required.
        boolean allIdentified = live.stream()
                .allMatch(v -> v.deviceId() != null && !v.deviceId().isBlank());
        if (!allIdentified) {
            return new Selection(SelectionState.OK, live.get(0), live, preferredDeviceId);
        }
        if (preferredDeviceId != null) {
            for (ConnectionView view : live) {
                if (preferredDeviceId.equals(view.deviceId())) {
                    return new Selection(SelectionState.OK, view, live, preferredDeviceId);
                }
            }
            // Preferred device is offline: fall back to the newest connection and mark it degraded
            // so callers can show a hint
            return new Selection(SelectionState.DEGRADED, live.get(0), live, preferredDeviceId);
        }
        return new Selection(SelectionState.NEEDS_SELECTION, null, live, null);
    }

    private void write(String username, ConnectionView view) {        try {
            RMap<String, String> map = connections(username);
            map.put(view.connectionId(), MAPPER.writeValueAsString(view));
            map.expire(KEY_TTL);
        } catch (Exception e) {
            log.warn("Write browser connection failed: username={}, device={}", username, view.deviceId(), e);
        }
    }

    /** Keeps only keepConnectionId for the same device, dropping stale connections left by extension reconnects. */
    private void pruneOtherConnectionsOfDevice(String username, String deviceId, String keepConnectionId) {
        try {
            RMap<String, String> map = connections(username);
            for (Map.Entry<String, String> entry : map.entrySet()) {
                if (entry.getKey().equals(keepConnectionId)) {
                    continue;
                }
                ConnectionView view = parse(entry.getValue());
                if (view != null && deviceId.equals(view.deviceId())) {
                    map.remove(entry.getKey());
                }
            }
        } catch (Exception e) {
            log.warn("Prune duplicate browser connections failed: username={}, device={}", username, deviceId, e);
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
