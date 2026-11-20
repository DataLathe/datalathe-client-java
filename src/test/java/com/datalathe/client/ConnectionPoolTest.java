package com.datalathe.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import static org.junit.jupiter.api.Assertions.assertSame;

import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import okhttp3.Call;
import okhttp3.ConnectionPool;
import okhttp3.EventListener;
import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ConnectionPoolTest {

    private static final long SHORTEST_COMMON_PROXY_IDLE_TIMEOUT_SECONDS = 60;

    private static final int BURST = 32;
    private static final String EMPTY_CHIP_LIST = "{\"chips\":[],\"metadata\":[]}";

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

    @Test
    void secondBurstOfConcurrentRequestsOpensNoNewConnections() throws Exception {
        AtomicInteger connects = new AtomicInteger();
        DatalatheClient counted = countingConnects(client, connects);
        ExecutorService callers = Executors.newFixedThreadPool(BURST);
        try {
            runBurst(counted, callers);
            assertEquals(BURST, connects.get(), "every request of the first burst is in flight at once");

            connects.set(0);
            runBurst(counted, callers);
            assertEquals(0, connects.get(), "the second burst should reuse the first burst's connections");
        } finally {
            callers.shutdownNow();
        }
    }

    @Test
    void correlationIdCopiesShareTheConnectionPool() throws Exception {
        AtomicInteger connects = new AtomicInteger();
        DatalatheClient counted = countingConnects(client, connects);
        DatalatheClient scoped = counted.withCorrelationId("run-1");
        server.enqueue(chipList());
        server.enqueue(chipList());

        counted.listChips();
        scoped.listChips();

        assertSame(client.httpClient().connectionPool(), scoped.httpClient().connectionPool());
        assertEquals(1, connects.get(), "the copy should reuse the connection its source opened");
    }

    @Test
    void callerSuppliedPoolIsUsedAsIs() {
        ConnectionPool pool = new ConnectionPool(2, 10, TimeUnit.SECONDS);

        DatalatheClient custom = new DatalatheClient(server.url("/").toString().replaceAll("/$", ""),
                Collections.emptyMap(), RetryConfig.DEFAULT, DatalatheClient.DEFAULT_CATALOG_TIMEOUT, pool);

        assertSame(pool, custom.httpClient().connectionPool());
        assertSame(pool, custom.withCorrelationId("run-1").httpClient().connectionPool());
    }

    private static MockResponse chipList() {
        return new MockResponse().setResponseCode(200)
                .setHeader("Content-Type", "application/json").setBody(EMPTY_CHIP_LIST);
    }

    private static DatalatheClient countingConnects(DatalatheClient base, AtomicInteger connects) {
        return base.copyWith(base.httpClient().newBuilder().eventListener(new EventListener() {
            @Override
            public void connectStart(Call call, InetSocketAddress address, Proxy proxy) {
                connects.incrementAndGet();
            }
        }).build());
    }

    // Holds every response until all requests have arrived, so none can reuse another's connection.
    private void runBurst(DatalatheClient caller, ExecutorService callers) throws Exception {
        CountDownLatch allArrived = new CountDownLatch(BURST);
        server.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) throws InterruptedException {
                allArrived.countDown();
                if (!allArrived.await(20, TimeUnit.SECONDS)) {
                    return new MockResponse().setResponseCode(500);
                }
                return chipList();
            }
        });
        List<Future<?>> calls = new ArrayList<>();
        for (int i = 0; i < BURST; i++) {
            calls.add(callers.submit(() -> caller.listChips()));
        }
        for (Future<?> call : calls) {
            call.get(30, TimeUnit.SECONDS);
        }
    }
}
