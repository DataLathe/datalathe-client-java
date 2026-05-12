package com.datalathe.client;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.Test;

class EmptyResponseBodyTest {

    @Test
    void emptyBodyOn200SurfacesAsIOException() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse().setResponseCode(200).setBody(""));
            server.start();
            DatalatheClient client = new DatalatheClient(
                    server.url("/").toString().replaceAll("/$", ""));
            IOException ex = assertThrows(IOException.class, () -> client.listChips());
            assertTrue(ex.getMessage().contains("200"), () -> "message was: " + ex.getMessage());
        }
    }

    @Test
    void nonJsonBodyOn200SurfacesAsIOException() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse().setResponseCode(200).setBody("<html>oops</html>"));
            server.start();
            DatalatheClient client = new DatalatheClient(
                    server.url("/").toString().replaceAll("/$", ""));
            IOException ex = assertThrows(IOException.class, () -> client.listChips());
            assertTrue(ex.getMessage().contains("oops"), () -> "message was: " + ex.getMessage());
        }
    }
}
