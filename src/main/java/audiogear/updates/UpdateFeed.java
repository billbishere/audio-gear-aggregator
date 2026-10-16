package audiogear.updates;

import audiogear.net.Http;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;

/** Reads the update manifest and the files it lists from the repository's data branch. No keys needed. */
public final class UpdateFeed {
    public static final String DEFAULT_BASE = "https://raw.githubusercontent.com/billbishere/audio-gear-aggregator/data/";

    private final Http http;
    private final String baseUrl;

    public UpdateFeed(Http http, String baseUrl) {
        this.http = http;
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl : baseUrl + "/";
    }

    public Manifest manifest() throws IOException {
        try {
            return Manifest.parse(http.get(baseUrl + "manifest.json", Map.of()));
        } catch (UncheckedIOException e) {
            throw e.getCause();
        }
    }

    /** The file's text, after checking it against the manifest's SHA-256 so a corrupt download is never imported. */
    public String download(Manifest.FileEntry f) throws IOException {
        String text = http.get(baseUrl + f.name().replace(" ", "%20"), Map.of());
        if (!sha256(text).equalsIgnoreCase(f.sha256())) throw new IOException("checksum mismatch for " + f.name());
        return text;
    }

    static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
