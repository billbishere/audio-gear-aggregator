package audiogear.feed;

import audiogear.domain.GearType;
import audiogear.importers.AutoEqClient;
import audiogear.importers.BrandParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/** The gear names the collector matches titles against: every AutoEq model plus the gear in research files. */
public final class GearIndex {
    private GearIndex() {}

    /** Over-ear measurements become headphones, everything else IEMs, as the AutoEq importer does. */
    public static List<KnownGear> fromAutoEq(AutoEqClient client, Set<String> excludeSources, BrandParser brands) throws IOException {
        List<KnownGear> out = new ArrayList<>();
        for (AutoEqClient.Source s : client.sources()) {
            if (excludeSources.contains(s.name())) continue;
            for (AutoEqClient.Entry e : client.entries(s)) {
                BrandParser.Name n = brands.parse(e.name());
                out.add(new KnownGear("over-ear".equals(e.form()) ? GearType.HEADPHONE : GearType.IEM, n.brand(), n.model()));
            }
        }
        return out;
    }

    /** Amps and DACs only come from here: AutoEq measures headphones and IEMs. */
    public static List<KnownGear> fromResearch(Path researchDir) throws IOException {
        List<KnownGear> out = new ArrayList<>();
        if (!Files.isDirectory(researchDir)) return out;
        List<Path> files;
        try (Stream<Path> s = Files.list(researchDir)) {
            files = s.filter(p -> p.toString().endsWith(".json")).sorted().toList();
        }
        ObjectMapper json = new ObjectMapper();
        for (Path f : files) {
            for (JsonNode item : json.readTree(f.toFile()).path("items")) {
                JsonNode g = item.path("gear");
                String brand = g.path("brand").asText(""), model = g.path("model").asText("");
                try {
                    if (!brand.isBlank() && !model.isBlank()) out.add(new KnownGear(GearType.valueOf(g.path("type").asText()), brand, model));
                } catch (IllegalArgumentException ignored) {
                    // unknown type: not something the feed can attach threads to
                }
            }
        }
        return out;
    }

    /** First occurrence wins; brand + model compared ignoring case. */
    @SafeVarargs
    public static List<KnownGear> merge(List<KnownGear>... lists) {
        Map<String, KnownGear> out = new LinkedHashMap<>();
        for (List<KnownGear> l : lists) {
            for (KnownGear g : l) out.putIfAbsent((g.brand() + "\u0000" + g.model()).toLowerCase(Locale.ROOT), g);
        }
        return List.copyOf(out.values());
    }
}
