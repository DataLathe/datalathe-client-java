package com.datalathe.client.resolver;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.RecordedRequest;

/**
 * A mock engine whose search, create and delete replies a test can hold back
 * and release, to order requests without sleeping. A chip is gone once a
 * delete of it succeeds or the test calls {@link #vanish}.
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
            return body == null ? new MockResponse().setResponseCode(code) : json(code, body);
        }
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Queue<Reply> searchReplies = new ConcurrentLinkedQueue<>();
    private final Map<String, Reply> createReplies = new ConcurrentHashMap<>();
    final List<RecordedRequest> searches = new CopyOnWriteArrayList<>();
    final List<JsonNode> creates = new CopyOnWriteArrayList<>();
    private final Map<String, Queue<Reply>> deleteReplies = new ConcurrentHashMap<>();
    private final Map<String, Queue<Integer>> lookupCodes = new ConcurrentHashMap<>();
    private final Set<String> gone = ConcurrentHashMap.newKeySet();
    final List<String> deletes = new CopyOnWriteArrayList<>();
    final List<String> lookups = new CopyOnWriteArrayList<>();
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

    /** Queues a held reply to the next delete of the chip; unscripted deletes succeed at once. */
    Reply heldDelete(String chipId) {
        return delete(chipId, new Reply(204, null, true));
    }

    /** Queues the failure an engine relays when the delete loses a race with another delete. */
    Reply failingDelete(String chipId) {
        return delete(chipId, new Reply(500, raceLost(chipId), false));
    }

    Reply heldFailingDelete(String chipId) {
        return delete(chipId, new Reply(500, raceLost(chipId), true));
    }

    private Reply delete(String chipId, Reply reply) {
        deleteReplies.computeIfAbsent(chipId, k -> new ConcurrentLinkedQueue<>()).add(reply);
        return reply;
    }

    private static String raceLost(String chipId) {
        return "\"Chip query failed: Chip manager returned status: 500 Internal Server Error DELETE /"
                + chipId + "\"";
    }

    /** Queues status codes for the next lookups of the chip; later lookups answer from the chip's state. */
    void lookup(String chipId, Integer... codes) {
        lookupCodes.computeIfAbsent(chipId, k -> new ConcurrentLinkedQueue<>()).addAll(List.of(codes));
    }

    void vanish(String chipId) {
        gone.add(chipId);
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
            String chipId = path.substring("/lathe/chips/".length());
            deletes.add(chipId);
            Queue<Reply> scripted = deleteReplies.get(chipId);
            Reply reply = scripted == null ? null : scripted.poll();
            if (reply == null) {
                return gone.add(chipId) ? new MockResponse().setResponseCode(204) : notFound(chipId);
            }
            MockResponse response = reply.respond();
            if (reply.code == 204) {
                gone.add(chipId);
            }
            return response;
        }
        if ("GET".equals(request.getMethod()) && path.startsWith("/lathe/chips/")) {
            String chipId = path.substring("/lathe/chips/".length());
            lookups.add(chipId);
            Queue<Integer> scripted = lookupCodes.get(chipId);
            Integer code = scripted == null ? null : scripted.poll();
            if (code == null) {
                code = gone.contains(chipId) ? 404 : 200;
            }
            if (code == 404) {
                return notFound(chipId);
            }
            return code == 200
                    ? json(200, found(chip(chipId, "listed", null), ""))
                    : json(code, "{\"error_code\":\"INTERNAL\",\"message\":\"lookup failed\"}");
        }
        others.add(request.getMethod() + " " + path);
        return json(404, "{}");
    }

    private static MockResponse notFound(String chipId) {
        return json(404, "{\"error_code\":\"chip_not_found\",\"chip_id\":\"" + chipId
                + "\",\"error\":\"gone\"}");
    }

    private static MockResponse json(int code, String body) {
        return new MockResponse()
                .setResponseCode(code)
                .setHeader("Content-Type", "application/json")
                .setBody(body);
    }
}
