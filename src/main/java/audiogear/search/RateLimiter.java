package audiogear.search;

/** Spaces calls at least minIntervalMillis apart (Brave's free tier allows about one request per second). */
public final class RateLimiter {

    private final long minIntervalMillis;
    private long next;

    public RateLimiter(long minIntervalMillis) { this.minIntervalMillis = minIntervalMillis; }

    public synchronized void acquire() throws InterruptedException {
        long now = System.currentTimeMillis();
        if (now < next) {
            Thread.sleep(next - now);
            now = System.currentTimeMillis();
        }
        next = now + minIntervalMillis;
    }
}
