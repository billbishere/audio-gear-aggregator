package audiogear.search;

import audiogear.db.GearRepository.GearRef;
import audiogear.domain.GearNames;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Finds catalog gear mentioned in a thread title or snippet. Names are compared with spaces and punctuation
 * removed. A model alone is enough when it has at least 4 characters and a digit ("hd650", "dt770");
 * otherwise the brand must also appear ("sony" + "z1r").
 */
public final class GearNameMatcher {

    private record Entry(long id, String brand, String model, boolean modelAlone) {}

    private final List<Entry> entries = new ArrayList<>();

    public GearNameMatcher(List<GearRef> refs) {
        for (GearRef r : refs) {
            String model = GearNames.normalize(r.model());
            if (model.isEmpty()) continue;
            boolean alone = model.length() >= 4 && model.chars().anyMatch(Character::isDigit);
            entries.add(new Entry(r.id(), GearNames.normalize(r.brand()), model, alone));
        }
    }

    public Set<Long> match(String text) {
        Set<Long> out = new LinkedHashSet<>();
        String t = GearNames.normalize(text);
        if (t.isEmpty()) return out;
        for (Entry e : entries) {
            if (t.contains(e.model) && (e.modelAlone || (!e.brand.isEmpty() && t.contains(e.brand)))) out.add(e.id);
        }
        return out;
    }
}
