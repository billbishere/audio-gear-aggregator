package audiogear.search;

import audiogear.domain.ThreadSource;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Canonical thread URLs, so the same discussion found twice is stored once:
 * Reddit posts become https://www.reddit.com/r/sub/comments/id/, XenForo threads (Head-Fi, ASR, SBAF) drop
 * page numbers and post anchors, YouTube videos become https://www.youtube.com/watch?v=id, and tracking
 * parameters and fragments are removed everywhere.
 */
public final class UrlNormalizer {
    private UrlNormalizer() {}

    private static final Pattern REDDIT_POST = Pattern.compile("^/r/([^/]+)/comments/([a-z0-9]+)(?:/.*)?$", Pattern.CASE_INSENSITIVE);
    private static final Pattern YOUTUBE_ID = Pattern.compile("(?:^|&)v=([A-Za-z0-9_-]{6,})");
    private static final Pattern XENFORO_THREAD = Pattern.compile("threads/(?:[^/?#]*\\.)?(\\d+)(?=[/?#&]|$)");
    private static final Set<String> TRACKING = Set.of("fbclid", "gclid", "ref", "ref_src", "share_id", "context", "rdt", "si");

    public static String siteFor(ThreadSource s) {
        return switch (s) {
            case REDDIT -> "reddit.com";
            case HEAD_FI -> "head-fi.org";
            case ASR -> "audiosciencereview.com";
            case SBAF -> "superbestaudiofriends.org";
            case YOUTUBE -> "youtube.com";
            case OTHER -> throw new IllegalArgumentException("OTHER has no site");
        };
    }

    public static ThreadSource classify(String url) {
        String host = host(url);
        if (host.equals("redd.it") || host.endsWith("reddit.com")) return ThreadSource.REDDIT;
        if (host.endsWith("head-fi.org")) return ThreadSource.HEAD_FI;
        if (host.endsWith("audiosciencereview.com")) return ThreadSource.ASR;
        if (host.endsWith("superbestaudiofriends.org")) return ThreadSource.SBAF;
        if (host.equals("youtu.be") || host.endsWith("youtube.com")) return ThreadSource.YOUTUBE;
        return ThreadSource.OTHER;
    }

    public static String normalize(String url) {
        URI u;
        try {
            u = URI.create(url.strip());
        } catch (IllegalArgumentException e) {
            return url.strip();
        }
        if (u.getHost() == null) return url.strip();
        String host = u.getHost().toLowerCase(Locale.ROOT);
        String path = u.getRawPath() == null || u.getRawPath().isEmpty() ? "/" : u.getRawPath();
        String query = u.getRawQuery();

        if (host.endsWith("reddit.com")) {
            Matcher m = REDDIT_POST.matcher(path);
            if (m.matches()) return "https://www.reddit.com/r/" + m.group(1) + "/comments/" + m.group(2).toLowerCase(Locale.ROOT) + "/";
            host = "www.reddit.com";
        }
        if (host.equals("youtu.be") && path.length() > 1) return "https://www.youtube.com/watch?v=" + path.substring(1).split("/")[0];
        if (host.endsWith("youtube.com")) {
            Matcher v = YOUTUBE_ID.matcher(query == null ? "" : query);
            if (path.equals("/watch") && v.find()) return "https://www.youtube.com/watch?v=" + v.group(1);
            if (path.startsWith("/shorts/")) return "https://www.youtube.com/watch?v=" + path.substring(8).split("/")[0];
            host = "www.youtube.com";
        }
        ThreadSource src = classify(url);
        if (src == ThreadSource.HEAD_FI || src == ThreadSource.ASR || src == ThreadSource.SBAF) {
            host = host.startsWith("www.") ? host : "www." + host;
            Matcher inPath = XENFORO_THREAD.matcher(path);
            if (inPath.find()) {
                return "https://" + host + path.substring(0, inPath.end()) + "/";
            }
            if (query != null) {
                Matcher inQuery = XENFORO_THREAD.matcher(query);
                if (inQuery.lookingAt()) return "https://" + host + path + "?" + query.substring(0, inQuery.end()) + "/";
            }
        }
        String cleaned = cleanQuery(query);
        return "https://" + host + path + (cleaned.isEmpty() ? "" : "?" + cleaned);
    }

    private static String cleanQuery(String query) {
        if (query == null || query.isEmpty()) return "";
        List<String> kept = new ArrayList<>();
        for (String p : query.split("&")) {
            String name = p.split("=", 2)[0].toLowerCase(Locale.ROOT);
            if (name.isEmpty() || name.startsWith("utm_") || TRACKING.contains(name)) continue;
            kept.add(p);
        }
        return String.join("&", kept);
    }

    private static String host(String url) {
        try {
            String h = URI.create(url.strip()).getHost();
            return h == null ? "" : h.toLowerCase(Locale.ROOT);
        } catch (IllegalArgumentException e) {
            return "";
        }
    }
}
