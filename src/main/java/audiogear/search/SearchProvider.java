package audiogear.search;

import audiogear.domain.ForumThread;
import audiogear.domain.ThreadSource;

import java.util.List;
import java.util.Set;

/** A community search source. Implementations must be thread-safe and return threads with normalised URLs. */
public interface SearchProvider {

    /** Stable id used in the search cache, e.g. "brave". */
    String id();

    String displayName();

    boolean isConfigured();

    /** @param sources the sources this provider should cover for this query (never empty) */
    List<ForumThread> search(String queryText, Set<ThreadSource> sources) throws SearchException;
}
