package audiogear.feed;

import audiogear.domain.ThreadSource;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/** One RSS/Atom feed the daily collector reads, as listed in feeds/sources.json. */
public record FeedSource(String name, String url, ThreadSource source) {

    private record SourcesFile(List<FeedSource> sources) {}

    /** Reads {"sources": [ {"name", "url", "source"} ]}. */
    public static List<FeedSource> load(Path json) throws IOException {
        SourcesFile f = new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
                .readValue(json.toFile(), SourcesFile.class);
        return f.sources() == null ? List.of() : List.copyOf(f.sources());
    }
}
