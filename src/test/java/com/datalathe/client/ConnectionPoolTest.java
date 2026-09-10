package com.datalathe.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.util.concurrent.TimeUnit;
import okhttp3.ConnectionPool;
import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ConnectionPoolTest {

    private static final long SHORTEST_COMMON_PROXY_IDLE_TIMEOUT_SECONDS = 60;

    private MockWebServer server;
    private DatalatheClient client;

    @BeforeEach
    void setUp() throws Exception {
        server = new MockWebServer();
        server.start();
        client = new DatalatheClient(server.url("/").toString().replaceAll("/$", ""));
    }

    @AfterEach
    void tearDown() throws Exception {
        server.shutdown();
    }

    private static OkHttpClient httpClientOf(DatalatheClient c) throws Exception {
        Field f = DatalatheClient.class.getDeclaredField("client");
        f.setAccessible(true);
        return (OkHttpClient) f.get(c);
    }

    private static long keepAliveNanos(ConnectionPool pool) throws Exception {
        Object target = pool;
        for (Field f : ConnectionPool.class.getDeclaredFields()) {
            if (f.getType().getName().contains("RealConnectionPool")) {
                f.setAccessible(true);
                target = f.get(pool);
                break;
            }
        }
        for (Field f : target.getClass().getDeclaredFields()) {
            if (f.getType() == long.class && f.getName().toLowerCase().contains("keepalive")) {
                f.setAccessible(true);
                return f.getLong(target);
            }
        }
        throw new AssertionError(
                "Could not read the pool's keep-alive from " + target.getClass()
                        + "; OkHttp internals changed, re-verify that the client still evicts idle "
                        + "connections before a proxy does");
    }

    @Test
    void idleConnectionsAreEvictedBeforeAnyCommonProxyIdleTimeout() throws Exception {
        long nanos = keepAliveNanos(httpClientOf(client).connectionPool());

        assertTrue(
                nanos < TimeUnit.SECONDS.toNanos(SHORTEST_COMMON_PROXY_IDLE_TIMEOUT_SECONDS),
                "idle keep-alive must stay below the shortest common proxy idle timeout ("
                        + SHORTEST_COMMON_PROXY_IDLE_TIMEOUT_SECONDS + "s) so a pooled connection is "
                        + "never reused after the far end has silently closed it; was "
                        + TimeUnit.NANOSECONDS.toSeconds(nanos) + "s");
    }

    @Test
    void defaultOkHttpPoolWouldNotSatisfyThatBound() throws Exception {
        long nanos = keepAliveNanos(new OkHttpClient().connectionPool());

        assertEquals(TimeUnit.MINUTES.toNanos(5), nanos,
                "OkHttp's default idle keep-alive is expected to be 5 minutes; if this changes the "
                        + "client's explicit pool may no longer be necessary");
    }

    @Test
    void connectionsAreStillPooledAcrossRequests() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(200)
                .setHeader("Content-Type", "application/json").setBody("{\"chips\":[],\"metadata\":[]}"));
        server.enqueue(new MockResponse().setResponseCode(200)
                .setHeader("Content-Type", "application/json").setBody("{\"chips\":[],\"metadata\":[]}"));

        client.listChips();
        client.listChips();

        ConnectionPool pool = httpClientOf(client).connectionPool();
        assertNotNull(pool);
        assertEquals(1, pool.connectionCount(), "both requests should reuse one pooled connection");
    }
}
