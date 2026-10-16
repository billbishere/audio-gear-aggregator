package audiogear.feed;

/** One entry of an RSS or Atom feed. published is a UTC date (yyyy-MM-dd) or null when the feed gives none. */
public record FeedItem(String url, String title, String published) {}
