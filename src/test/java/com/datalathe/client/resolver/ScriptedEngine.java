package com.datalathe.client.resolver;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.RecordedRequest;

/**
 * A mock engine whose search and create replies a test can hold back and
 * release, to order requests without sleeping.
 */
final class ScriptedEngine extends Dispatcher {

    static final String NO_CHIPS = "{\"chips\":[],\"metadata\":[],\"tags\":[]}";

    static final class Reply {
        private final int code;
        private final String body;
        private final CountDownLatch gate;
        private final CountDownLatch arrived = new CountDownLatch(1);

        private Reply(int code, String body, boolean held) {
            this.code = code;
            this.body = body;
            this.gate = new CountDownLatch(held ? 1 : 0);
        }

        void awaitArrived() throws InterruptedException {
            if (!arrived.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError("request did not arrive");
            }
        }

        void release() {
            gate.countDown();
        }

        private MockResponse respond() throws InterruptedException {
            arrived.countDown();
            if (!gate.await(10, TimeUnit.SECONDS)) {
                throw new AssertionError("reply was never released");
            }
            return json(code, body);
        }
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Queue<Reply> searchReplies = new ConcurrentLinkedQueue<>();
    private final Map<String, Reply> createReplies = new ConcurrentHashMap<>();
    final List<RecordedRequest> searches = new CopyOnWriteArrayList<>();
    final List<JsonNode> creates = new CopyOnWriteArrayList<>();
    final List<String> deletes = new CopyOnWriteArrayList<>();
    final List<String> others = new CopyOnWriteArrayList<>();

    static String chip(String id, String table, String partitionValue) {
        String pv = partitionValue == null ? "null" : "\"" + partitionValue + "\"";
        return "{\"chip_id\":\"" + id + "\",\"sub_chip_id\":\"" + id + "\","
                + "\"table_name\":\"" + table + "\",\"partition_value\":" + pv + "}";
    }

    static String tag(String chipId, String key, String value) {
        return "{\"chip_id\":\"" + chipId + "\",\"key\":\"" + key + "\",\"value\":\"" + value + "\"}";
    }

    static String found(String chipsJson, String tagsJson) {
        return "{\"chips\":[" + chipsJson + "],\"metadata\":[],\"tags\":[" + tagsJson + "]}";
    }

    /** Queues the reply to the next search that has no reply yet. */
    Reply search(String body) {
        return queue(new Reply(200, body, false));
    }

    Reply heldSearch(String body) {
        return queue(new Reply(200, body, true));
    }

    Reply heldFailingSearch() {
        return queue(new Reply(500, "{\"error\":\"search failed\"}", true));
    }

    private Reply queue(Reply reply) {
        searchReplies.add(reply);
        return reply;
    }

    /** Sets the reply to creates for the table; the key is {@code table} or {@code table|partition}. */
    Reply create(String key, String chipId) {
        return create(key, chipId, false);
    }

    Reply heldCreate(String key, String chipId) {
        return create(key, chipId, true);
    }

    private Reply create(String key, String chipId, boolean held) {
        Reply reply = new Reply(200, "{\"chip_id\":\"" + chipId + "\"}", held);
        createReplies.put(key, reply);
        return reply;
    }

    @Override
    public MockResponse dispatch(RecordedRequest request) throws InterruptedException {
        String path = request.getPath();
        if ("GET".equals(request.getMethod()) && path.startsWith("/lathe/chips/search")) {
            searches.add(request);
            Reply reply = searchReplies.poll();
            return reply == null ? json(200, NO_CHIPS) : reply.respond();
        }
        if ("POST".equals(request.getMethod()) && path.equals("/lathe/stage/data")) {
            JsonNode body;
            try {
                body = MAPPER.readTree(request.getBody().readUtf8());
            } catch (Exception e) {
                throw new AssertionError(e);
            }
            creates.add(body);
            JsonNode source = body.get("source_request");
            String table = source.get("table_name").asText();
            JsonNode partition = source.get("partition");
            String key = partition == null || partition.isNull()
                    ? table
                    : table + "|" + partition.get("partition_values").get(0).asText();
            Reply reply = createReplies.get(key);
            return reply == null ? json(500, "{\"error\":\"no create scripted for " + key + "\"}") : reply.respond();
        }
        if ("DELETE".equals(request.getMethod()) && path.startsWith("/lathe/chips/")) {
            deletes.add(path.substring("/lathe/chips/".length()));
            return new MockResponse().setResponseCode(204);
        }
        others.add(request.getMethod() + " " + path);
        return json(404, "{}");
    }

    private static MockResponse json(int code, String body) {
        return new MockResponse()
                .setResponseCode(code)
                .setHeader("Content-Type", "application/json")
                .setBody(body);
    }
}
