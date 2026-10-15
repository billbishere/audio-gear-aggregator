package audiogear.testing;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/** A local HTTP server that replays canned responses by exact path + query, and records every request. */
public final class StubServer implements AutoCloseable {

    public record Response(int status, String body, Map<String, String> headers) {
        public static Response ok(String body) { return new Response(200, body, Map.of()); }
    }

    public record Request(String method, String pathAndQuery, Map<String, List<String>> headers, String body) {
        public String header(String name) {
            for (var e : headers.entrySet()) if (e.getKey().equalsIgnoreCase(name)) return e.getValue().getFirst();
            return null;
        }
    }

    private final HttpServer server;
    private final Map<String, Response> routes = new ConcurrentHashMap<>();
    public final List<Request> requests = new CopyOnWriteArrayList<>();

    public StubServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();
    }

    public String base() { return "http://127.0.0.1:" + server.getAddress().getPort(); }

    /** pathAndQuery is the raw (still percent-encoded) path, plus "?query" when present. */
    public StubServer on(String pathAndQuery, Response r) {
        routes.put(pathAndQuery, r);
        return this;
    }

    public StubServer on(String pathAndQuery, String body) { return on(pathAndQuery, Response.ok(body)); }

    private void handle(HttpExchange ex) throws IOException {
        String raw = ex.getRequestURI().getRawPath() + (ex.getRequestURI().getRawQuery() == null ? "" : "?" + ex.getRequestURI().getRawQuery());
        String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        requests.add(new Request(ex.getRequestMethod(), raw, Map.copyOf(ex.getRequestHeaders()), body));
        Response r = routes.getOrDefault(raw, new Response(404, "not found: " + raw, Map.of()));
        byte[] bytes = r.body().getBytes(StandardCharsets.UTF_8);
        r.headers().forEach((k, v) -> ex.getResponseHeaders().add(k, v));
        ex.sendResponseHeaders(r.status(), bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }

    public long count(String pathAndQuery) {
        return requests.stream().filter(q -> q.pathAndQuery().equals(pathAndQuery)).count();
    }

    @Override public void close() { server.stop(0); }
}
