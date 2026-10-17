package audiogear.feed;

import audiogear.db.CurveRepository;
import audiogear.db.Database;
import audiogear.db.GearRepository;
import audiogear.db.ThreadRepository;
import audiogear.domain.GearDetail;
import audiogear.importers.Progress;
import audiogear.importers.ResearchImporter;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.StringReader;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class AsrIndexTest {

    @TempDir Path dir;

    static List<AsrIndex.Device> devices() throws Exception {
        return AsrIndex.parse(RssReaderTest.fixture("asr-electronics.html"));
    }

    static Map<String, ResearchImporter.ResearchItem> byName(List<ResearchImporter.ResearchItem> items) {
        return items.stream().collect(Collectors.toMap(i -> i.gear().get("brand") + " " + i.gear().get("model"), Function.identity()));
    }

    @Test
    void parsesEveryRowByColumnName() throws Exception {
        List<AsrIndex.Device> d = devices();
        assertEquals(7, d.size());
        assertEquals(new AsrIndex.Device("DAC+Headphone AMP", "Topping", "DX1 II", 121.0, 119.0, "amirm", true,
                LocalDate.parse("2026-06-24"), "https://www.audiosciencereview.com/forum/index.php?threads/topping-dx1-ii-dac-hp-amp-review.72034/"),
                d.getFirst());
        AsrIndex.Device r100 = d.get(5);
        assertNull(r100.sinad());
        assertNull(r100.priceUsd());
    }

    @Test
    void mapsAmpAndDacTypesAndSkipsTheRest() throws Exception {
        Map<String, ResearchImporter.ResearchItem> items = byName(AsrIndex.toItems(devices()));
        assertEquals(java.util.Set.of("Topping DX1 II", "Schiit Magni Unity", "Apple USB-C to 3.5 mm Adapter", "Fosi Audio V3", "SMSL R100"),
                items.keySet()); // the CD player is left out

        Map<String, String> dx1 = items.get("Topping DX1 II").gear();
        assertEquals("AMP_DAC", dx1.get("type"));
        assertEquals("121", dx1.get("sinad_db"));
        assertEquals("119", dx1.get("msrp"));
        assertEquals("USD", dx1.get("msrp_currency"));
        assertTrue(dx1.get("tags").contains("asr-reviewed"));
        assertTrue(dx1.get("tags").contains("asr-recommended"));

        Map<String, String> magni = items.get("Schiit Magni Unity").gear();
        assertEquals("AMP", magni.get("type"));
        assertNull(magni.get("sinad_db")); // only DACs store SINAD as a spec
        assertTrue(magni.get("notes").contains("SINAD 115 dB"));
        assertFalse(magni.get("tags").contains("asr-recommended"));

        assertEquals("AMP_DAC", items.get("Apple USB-C to 3.5 mm Adapter").gear().get("type"));
        assertTrue(items.get("Apple USB-C to 3.5 mm Adapter").gear().get("tags").contains("dongle"));
        assertEquals("AMP", items.get("Fosi Audio V3").gear().get("type"));
        assertTrue(items.get("Fosi Audio V3").gear().get("tags").contains("speaker-amp"));
        assertEquals("DAC", items.get("SMSL R100").gear().get("type"));
        assertNull(items.get("SMSL R100").gear().get("msrp"));
    }

    @Test
    void mergesRepeatReviewsOfTheSameModel() throws Exception {
        ResearchImporter.ResearchItem dx1 = byName(AsrIndex.toItems(devices())).get("Topping DX1 II");
        assertEquals(2, dx1.threads().size());
        assertEquals("121", dx1.gear().get("sinad_db")); // specs from the most recent review
        assertEquals("2026-06-24", dx1.threads().getFirst().posted());
        assertEquals("ASR", dx1.threads().getFirst().source());
    }

    @Test
    void reviewedSinceKeepsOnlyRecentReviews() throws Exception {
        List<AsrIndex.Device> recent = AsrIndex.reviewedSince(devices(), LocalDate.parse("2026-06-01"));
        assertEquals(List.of("DX1 II", "R100"), recent.stream().map(AsrIndex.Device::model).toList());
    }

    @Test
    void itemsImportCleanly() throws Exception {
        var file = new FeedFile("ASR amps and DACs", "2026-10-17", null, "test", AsrIndex.toItems(devices()));
        String json = new ObjectMapper().setSerializationInclusion(JsonInclude.Include.NON_NULL).writeValueAsString(file);
        Database db = Database.open(dir.resolve("a.db"), dir.resolve("b"));
        GearRepository gear = new GearRepository(db);
        ResearchImporter.Result r = new ResearchImporter(gear, new ThreadRepository(db), new CurveRepository(db), null, null)
                .importJson(new StringReader(json), Progress.NONE);
        assertEquals(List.of(), r.summary().problems());
        assertEquals(5, gear.count());
        GearDetail dx1 = gear.get(gear.findId("Topping", "DX1 II", "").orElseThrow()).orElseThrow();
        assertEquals(121.0, dx1.dac().sinadDb());
    }
}
