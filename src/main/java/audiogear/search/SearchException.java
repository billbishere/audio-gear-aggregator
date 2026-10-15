package audiogear.search;

import audiogear.net.Http;

import java.io.IOException;
import java.net.http.HttpTimeoutException;

/** A provider failure with a message fit for the UI banner. retryAfterSeconds is set for rate limits. */
public final class SearchException extends Exception {

    private final Long retryAfterSeconds;

    public SearchException(String message, Long retryAfterSeconds, Throwable cause) {
        super(message, cause);
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public Long retryAfterSeconds() { return retryAfterSeconds; }

    /** Translates transport errors into plain messages. */
    public static SearchException from(String provider, IOException e) {
        if (e instanceof Http.HttpStatusException s) {
            return switch (s.status()) {
                case 401, 403 -> new SearchException(provider + ": the API key or credentials were rejected (" + s.status() + ")", null, e);
                case 429 -> new SearchException(provider + ": rate limited", s.retryAfterSeconds() == null ? 60L : s.retryAfterSeconds(), e);
                default -> new SearchException(provider + ": " + e.getMessage(), null, e);
            };
        }
        if (e instanceof HttpTimeoutException) return new SearchException(provider + ": timed out after 10 s", null, e);
        return new SearchException(provider + ": " + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()), null, e);
    }
}
