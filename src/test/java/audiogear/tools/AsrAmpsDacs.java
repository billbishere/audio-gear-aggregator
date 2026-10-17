package audiogear.tools;

import audiogear.feed.AsrIndex;
import audiogear.feed.FeedFile;
import audiogear.importers.ResearchImporter;
import audiogear.net.Http;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Writes a research file with every amp and DAC Audio Science Review has measured (type, SINAD, price, review
 * links), from its review database. Usage: AsrAmpsDacs &lt;out.json&gt;. New reviews after that arrive through the
 * daily feed.
 */
public final class AsrAmpsDacs {
    private AsrAmpsDacs() {}

    public static void main(String[] args) throws Exception {
        Path out = Path.of(args[0]);
        List<AsrIndex.Device> devices = AsrIndex.parse(new Http().get(AsrIndex.URL, Map.of()));
        List<ResearchImporter.ResearchItem> items = AsrIndex.toItems(devices);
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        FeedFile file = new FeedFile("Amps and DACs measured by Audio Science Review", today.toString(), null,
                "Every headphone amp, DAC, DAC/amp, dongle, DAP, audio interface and speaker amp in Audio Science Review's "
                        + "measurement database (asrdata), with SINAD, price and the review links. Specs come from the newest "
                        + "review of each model; snippets summarise the measurements in our own words.", items);
        String json = new ObjectMapper().setSerializationInclusion(JsonInclude.Include.NON_NULL)
                .enable(SerializationFeature.INDENT_OUTPUT).writeValueAsString(file).replace("\r\n", "\n");
        Files.writeString(out, json + "\n", StandardCharsets.UTF_8);

        Map<String, Integer> byType = new TreeMap<>();
        items.forEach(i -> byType.merge(i.gear().get("type"), 1, Integer::sum));
        System.out.println("ASR devices listed: " + devices.size() + "; amps and DACs written: " + items.size() + " " + byType
                + "; review links: " + items.stream().mapToInt(i -> i.threads().size()).sum() + " -> " + out);
    }
}
