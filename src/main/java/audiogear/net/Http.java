package audiogear.net;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

/** Thin HttpClient wrapper: 10 s timeouts, a fixed User-Agent, and errors as {@link HttpStatusException}. */
public final class Http {

    public static final Duration TIMEOUT = Duration.ofSeconds(10);
    public static final String USER_AGENT = "AudioGearAggregator/1.0 (personal desktop app)";

    /** A non-2xx response. retryAfterSeconds comes from the Retry-After header when present. */
    public static final class HttpStatusException extends IOException {
        private final int status;
        private final Long retryAfterSeconds;

        public HttpStatusException(int status, String message, Long retryAfterSeconds) {
            super("HTTP " + status + (message == null || message.isBlank() ? "" : ": " + message));
            this.status = status;
            this.retryAfterSeconds = retryAfterSeconds;
        }

        public int status() { return status; }
        public Long retryAfterSeconds() { return retryAfterSeconds; }
    }

    private final HttpClient client;

    public Http() {
        this(HttpClient.newBuilder().connectTimeout(TIMEOUT).followRedirects(HttpClient.Redirect.NORMAL).build());
    }

    public Http(HttpClient client) { this.client = client; }

    public String get(String url, Map<String, String> headers) throws IOException {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url)).timeout(TIMEOUT).GET();
        return send(b, headers);
    }

    public String postForm(String url, Map<String, String> form, Map<String, String> headers) throws IOException {
        StringBuilder body = new StringBuilder();
        for (var e : form.entrySet()) {
            if (!body.isEmpty()) body.append('&');
            body.append(encode(e.getKey())).append('=').append(encode(e.getValue()));
        }
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url)).timeout(TIMEOUT)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()));
        return send(b, headers);
    }

    private String send(HttpRequest.Builder b, Map<String, String> headers) throws IOException {
        b.header("User-Agent", USER_AGENT);
        headers.forEach(b::setHeader);
        HttpResponse<String> r;
        try {
            r = client.send(b.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted", e);
        }
        if (r.statusCode() / 100 != 2) {
            Long retry = r.headers().firstValue("Retry-After").map(Http::parseLong).orElse(null);
            String body = r.body() == null ? "" : r.body().strip();
            throw new HttpStatusException(r.statusCode(), body.length() > 200 ? body.substring(0, 200) : body, retry);
        }
        return r.body();
    }

    private static Long parseLong(String s) {
        try {
            return Long.parseLong(s.strip());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Percent-encoding for query values and path segments (spaces become %20, not '+'). */
    public static String encode(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
