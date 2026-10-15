package audiogear.domain;

/** A community discussion thread as returned by a search API (no page scraping). */
public record ForumThread(Long id, ThreadSource source, String url, String title, String snippet, String author,
                          String postedAt, Integer score, Integer commentCount, String firstSeenAt, String lastSeenAt) {}
