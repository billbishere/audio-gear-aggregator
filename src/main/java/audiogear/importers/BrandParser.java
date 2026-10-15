package audiogear.importers;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Splits a measurement name such as "Campfire Audio Andromeda (2020)" into brand "Campfire Audio",
 * model "Andromeda" and label "2020". Multi-word brands come from brands.txt; otherwise the first word is the brand.
 */
public final class BrandParser {

    public record Name(String brand, String model, String label) {}

    private static final Pattern TRAILING_PARENS = Pattern.compile("^(.*?)\\s*\\(([^()]*)\\)\\s*$");
    private static final Set<String> COLLAB = Set.of("x", "×", "+");

    private final List<String> multiWordBrands;

    public BrandParser(List<String> multiWordBrands) {
        this.multiWordBrands = new ArrayList<>(multiWordBrands);
        this.multiWordBrands.sort(Comparator.comparingInt(String::length).reversed());
    }

    public static BrandParser standard() {
        List<String> brands = new ArrayList<>();
        try (InputStream in = BrandParser.class.getResourceAsStream("/audiogear/importers/brands.txt")) {
            if (in != null) {
                BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
                for (String line; (line = r.readLine()) != null; ) {
                    String t = line.strip();
                    if (!t.isEmpty() && !t.startsWith("#")) brands.add(t);
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read brands.txt", e);
        }
        return new BrandParser(brands);
    }

    public Name parse(String fullName) {
        String name = fullName.strip().replaceAll("\\s+", " ");
        List<String> labels = new ArrayList<>();
        Matcher m;
        while ((m = TRAILING_PARENS.matcher(name)).matches() && !m.group(1).isBlank()) {
            labels.addFirst(m.group(2).strip());
            name = m.group(1).strip();
        }
        String label = String.join(", ", labels);

        String lower = name.toLowerCase(Locale.ROOT);
        for (String b : multiWordBrands) {
            String bl = b.toLowerCase(Locale.ROOT);
            if (lower.startsWith(bl + " ") && name.length() > b.length() + 1) {
                return new Name(name.substring(0, b.length()), name.substring(b.length() + 1).strip(), label);
            }
        }
        String[] words = name.split(" ");
        if (words.length >= 4 && COLLAB.contains(words[1].toLowerCase(Locale.ROOT))) {
            return new Name(words[0] + " " + words[1] + " " + words[2], String.join(" ", List.of(words).subList(3, words.length)), label);
        }
        if (words.length == 1) return new Name("Unknown", name, label);
        return new Name(words[0], name.substring(words[0].length() + 1), label);
    }
}
