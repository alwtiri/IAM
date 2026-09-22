package com.enterprise.iam.providers.genericrest;

import com.enterprise.iam.kernel.Json;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/** Minimal in-memory SCIM 2.0 service provider for protocol-level tests (users, paging, filter, PATCH active). */
final class FakeScimServer implements AutoCloseable {

    final Map<String, Map<String, Object>> users = new LinkedHashMap<>();
    final AtomicInteger patches = new AtomicInteger();
    volatile String expectedAuth = "Bearer test-token";
    volatile boolean ignorePatch;
    volatile int forceStatus;
    private final HttpServer server;

    FakeScimServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/scim/v2/", this::handle);
        server.start();
    }

    String endpoint() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/scim/v2";
    }

    void addUser(String id, String userName, boolean active, String... groups) {
        Map<String, Object> u = new LinkedHashMap<>();
        u.put("schemas", List.of("urn:ietf:params:scim:schemas:core:2.0:User"));
        u.put("id", id);
        u.put("userName", userName);
        u.put("displayName", userName.toUpperCase());
        u.put("active", active);
        List<Map<String, Object>> gl = new ArrayList<>();
        for (String g : groups) {
            gl.add(Map.of("value", "g-" + g, "display", g));
        }
        u.put("groups", gl);
        users.put(id, u);
    }

    private void handle(HttpExchange ex) throws IOException {
        if (!expectedAuth.equals(ex.getRequestHeaders().getFirst("Authorization"))) {
            send(ex, 401, Map.of("detail", "unauthorized"));
            return;
        }
        if (forceStatus != 0) {
            send(ex, forceStatus, Map.of("detail", "forced"));
            return;
        }
        String path = ex.getRequestURI().getPath().substring("/scim/v2/".length());
        String query = ex.getRequestURI().getRawQuery();
        Map<String, String> q = new LinkedHashMap<>();
        if (query != null) {
            for (String kv : query.split("&")) {
                String[] p = kv.split("=", 2);
                q.put(p[0], p.length > 1 ? URLDecoder.decode(p[1], StandardCharsets.UTF_8) : "");
            }
        }
        switch (ex.getRequestMethod()) {
            case "GET" -> {
                if (path.equals("ServiceProviderConfig")) {
                    send(ex, 200, Map.of("patch", Map.of("supported", true)));
                } else if (path.equals("Users")) {
                    List<Object> all = new ArrayList<>(users.values());
                    if (q.containsKey("filter")) {
                        String name = q.get("filter").replaceAll("^userName eq \"(.*)\"$", "$1");
                        all.removeIf(u -> !name.equals(((Map<?, ?>) u).get("userName")));
                    }
                    int start = Integer.parseInt(q.getOrDefault("startIndex", "1"));
                    int count = Integer.parseInt(q.getOrDefault("count", "100"));
                    List<Object> page = all.subList(Math.min(all.size(), start - 1), Math.min(all.size(), start - 1 + count));
                    send(ex, 200, Map.of("totalResults", all.size(), "startIndex", start, "itemsPerPage", page.size(), "Resources", page));
                } else if (path.startsWith("Users/")) {
                    Map<String, Object> u = users.get(URLDecoder.decode(path.substring(6), StandardCharsets.UTF_8));
                    send(ex, u == null ? 404 : 200, u == null ? Map.of("detail", "not found") : u);
                } else {
                    send(ex, 404, Map.of());
                }
            }
            case "PATCH" -> {
                Map<String, Object> u = users.get(URLDecoder.decode(path.substring(6), StandardCharsets.UTF_8));
                Map<String, Object> body = Json.parseObject(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                patches.incrementAndGet();
                if (u == null) {
                    send(ex, 404, Map.of());
                    return;
                }
                if (!ignorePatch) {
                    Object value = ((Map<?, ?>) ((List<?>) body.get("Operations")).get(0)).get("value");
                    u.put("active", value);
                }
                send(ex, 200, u);
            }
            default -> send(ex, 405, Map.of());
        }
    }

    private static void send(HttpExchange ex, int status, Object body) throws IOException {
        byte[] bytes = Json.write(body).getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/scim+json");
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
