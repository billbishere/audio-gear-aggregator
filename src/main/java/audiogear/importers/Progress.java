package audiogear.importers;

/** Progress callback for long imports; implementations must be thread-safe. */
public interface Progress {
    void update(long done, long total, String message);

    boolean isCancelled();

    Progress NONE = new Progress() {
        @Override public void update(long done, long total, String message) {}
        @Override public boolean isCancelled() { return false; }
    };
}
