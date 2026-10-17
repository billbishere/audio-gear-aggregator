"""Extracts headphone-amp output power and output impedance for the catalog's amps and DAC/amps from
(1) Audio Science Review review text (measured) and (2) retailers' Shopify product descriptions (manufacturer specs).
Usage: amp_specs.py <research asr file> <shops dir> <reviews dir> <out json>"""
import glob, html, json, os, re, sys
from collections import defaultdict

sys.stdout.reconfigure(encoding="utf-8")
research, shops_dir, reviews_dir, out_path = sys.argv[1:5]


def norm(s):
    return re.sub(r"[^a-z0-9]", "", s.lower())


def word_pattern(text):
    core = norm(text)
    if not core:
        return None
    return re.compile(r"(?<![a-z0-9])" + r"[^a-z0-9]*".join(re.escape(c) for c in core) + r"(?![a-z0-9])", re.I)


def clean(body):
    t = html.unescape(re.sub(r"<[^>]+>", " ", body or ""))
    return re.sub(r"\s+", " ", t)


OHM = r"(?:Ω|ω|ohms?\b|Ohm\b|OHM\b|R\b)"
NUM = r"(\d{1,3}(?:,\d{3})+|\d{1,5}(?:[.,]\d+)?)"
UNIT = r"(mW|mw|W|watts?|milliwatts?)\b"
POWER = [
    # 1000mW @ 32Ω | 1.2W x2 at 32 ohms | 250mW/32Ω | 2000mW (32Ω load)
    re.compile(NUM + r"\s*" + UNIT + r"\s*(?:[x×]\s*2\s*)?(?:per channel\s*)?(?:@|at|into|/|\(|under)\s*" + NUM + r"\s*" + OHM, re.I),
    # 32Ω: 1000mW | into 300 ohm is healthy 248 milliwatts
    re.compile(r"(?:@|at|into)?\s*" + NUM + r"\s*" + OHM + r"\s*(?:load)?\s*[:=,]?\s*(?:is|of|=|:|-)?\s*(?:a\s+)?"
               r"(?:healthy|good|solid|nice|very\s+good)?\s*(?:about|around|~|≈)?\s*" + NUM + r"\s*" + UNIT, re.I),
]
TEST_CONDITION = re.compile(r"(thd|%|snr|dynamic range|noise|output\s*=)", re.I)
IMPEDANCE = re.compile(r"output impedance[^.;]{0,15}?[:=]?\s*(?:is\s*)?(?:about\s*|around\s*|only\s*|just\s*)?(?:<|≤|less than|under|below)?\s*"
                       r"(\d{1,3}(?:\.\d+)?)\s*(mΩ|milliohms?|Ω|ω|ohms?\b|Ohm\b|R\b)(?!\s*(?:headphones?|cans|loads?|iems?|earphones?))", re.I)
BALANCED = re.compile(r"(?<!un)balanced|4\.4\s*mm|xlr|\bbal\b|2\.5\s*mm", re.I)
SINGLE = re.compile(r"single[- ]ended|3\.5\s*mm|6\.35|6\.3\s*mm|1/4|\bse\b|unbalanced", re.I)
# "Hip-DAC 3" is not the "hip dac" and "Mojo 2" is not the "Mojo": a following version marker means a different model
VARIANT = re.compile(r"[\s-]*(?:\d|\+|(?:ii|iii|iv|pro|plus|max|ultra|mk\s*\d|v\d|gen|se|s|x|lite|mini|evo|signature|limited|special)\b)", re.I)


def number(s):
    s = s.strip()
    if re.fullmatch(r"\d{1,3}(?:,\d{3})+", s):
        return float(s.replace(",", ""))
    return float(s.replace(",", "."))


def side(text, start, end):
    """A balanced/single-ended keyword just before the value wins; else one just after; else single-ended."""
    before = text[max(0, start - 35):start]
    hits = [(len(before) - k.end(), "BALANCED") for k in BALANCED.finditer(before)] + \
           [(len(before) - k.end(), "SE") for k in SINGLE.finditer(before)]
    if hits:
        return min(hits)[1]
    after = text[end:end + 20]
    hits = [(k.start(), "BALANCED") for k in BALANCED.finditer(after)] + [(k.start(), "SE") for k in SINGLE.finditer(after)]
    return min(hits)[1] if hits else "SE"


def powers(text, evidence):
    found = {}
    for i, p in enumerate(POWER):
        for m in p.finditer(text):
            if i == 0:
                val, unit, load = m.group(1), m.group(2), m.group(3)
            else:
                load, val, unit = m.group(1), m.group(2), m.group(3)
            if TEST_CONDITION.search(text[max(0, m.start() - 25):m.start()]):
                continue  # "THD+N <0.0001% @145mW (32Ω)" is a measurement condition, not the amp's power
            try:
                mw = number(val) * (1000 if unit.lower().startswith("w") else 1)
                ohm = number(load)
            except ValueError:
                continue
            if not (8 <= ohm <= 1000) or not (5 <= mw <= 20000):
                continue
            key = (side(text, m.start(), m.end()), int(ohm) if ohm == int(ohm) else ohm)
            if key not in found:
                found[key] = round(mw)
                evidence.append(text[max(0, m.start() - 50):m.end() + 25])
    return found


def impedance(text, evidence):
    for m in IMPEDANCE.finditer(text):
        v = float(m.group(1)) / (1000 if m.group(2).lower().startswith("m") else 1)
        if 0 <= v <= 30:  # higher values are usually a misread (a headphone's impedance, milliohms)
            evidence.append(text[max(0, m.start() - 10):m.end() + 15])
            return v
    return None


items = json.load(open(research, encoding="utf-8"))["items"]
targets = [i for i in items if i["gear"]["type"] in ("AMP", "AMP_DAC") and "speaker-amp" not in i["gear"]["tags"]]
by_brand = defaultdict(list)
for t in targets:
    by_brand[norm(t["gear"]["brand"])].append(t)
for lst in by_brand.values():
    lst.sort(key=lambda t: -len(norm(t["gear"]["model"])))


def match(title, vendor):
    if " / " in title:  # "R3 II / R3 Gen2": several models in one listing, can't tell which specs are whose
        return None
    title_n = norm(title)
    for brand_key, lst in by_brand.items():
        if brand_key not in title_n and brand_key != norm(vendor or ""):
            continue
        for t in lst:  # longest model first
            p = word_pattern(t["gear"]["model"])
            for m in p.finditer(title) if p else []:
                if not VARIANT.match(title[m.end():]):
                    return t
    return None


specs = defaultdict(lambda: {"power": {}, "z": None, "sources": [], "evidence": []})

# (2) retailer descriptions = manufacturer specs
for path in sorted(glob.glob(os.path.join(shops_dir, "*.json"))):
    shop = os.path.basename(path).rsplit("-", 1)[0]
    for p in json.load(open(path, encoding="utf-8"))["products"]:
        t = match(p["title"], p.get("vendor"))
        if not t:
            continue
        text, ev = clean(p.get("body_html")), []
        pw, z = powers(text, ev), impedance(text, ev)
        if not pw and z is None:
            continue
        s = specs[(t["gear"]["brand"], t["gear"]["model"])]
        for k, v in pw.items():
            s["power"].setdefault(k, v)
        if s["z"] is None and z is not None:
            s["z"] = z
        s["sources"].append(f"https://{shop}/products/{p['handle']}")
        s["evidence"] += [p["title"] + " :: " + e for e in ev]

# (1) ASR review text: measured, so it wins for the loads it reports
for t in targets:
    for th in t["threads"]:
        f = os.path.join(reviews_dir, re.sub(r"[^\w.-]+", "_", th["url"].split("threads/")[-1]).strip("_")[:120] + ".html")
        if not os.path.exists(f):
            continue
        page = open(f, encoding="utf-8", errors="replace").read()
        m = re.search(r'<div class="bbWrapper">(.*?)</article>', page, re.S)
        text, ev = clean(m.group(1) if m else ""), []
        pw, z = powers(text, ev), impedance(text, ev)
        if not pw and z is None:
            continue
        s = specs[(t["gear"]["brand"], t["gear"]["model"])]
        s["power"].update(pw)
        if z is not None:
            s["z"] = z
        s["sources"].append(th["url"])
        s["evidence"] += ["ASR :: " + e for e in ev]

out_items = []
for t in targets:
    key = (t["gear"]["brand"], t["gear"]["model"])
    if key not in specs:
        continue
    s = specs[key]
    gear = {"type": t["gear"]["type"], "brand": key[0], "model": key[1]}
    if s["power"]:
        gear["amp_power"] = "; ".join(f"{o}:{l}={mw}" for (o, l), mw in sorted(s["power"].items(), key=lambda kv: (kv[0][0], kv[0][1])))
    if s["z"] is not None:
        gear["output_impedance_ohm"] = str(int(s["z"])) if s["z"] == int(s["z"]) else str(round(s["z"], 3))
    if len(gear) > 3:
        out_items.append({"gear": gear, "sources": sorted(set(s["sources"])), "evidence": s["evidence"]})

print(f"headphone amps/DAC-amps: {len(targets)}; with any spec: {len(out_items)}; "
      f"with power: {sum('amp_power' in i['gear'] for i in out_items)}; with output impedance: {sum('output_impedance_ohm' in i['gear'] for i in out_items)}")
json.dump(out_items, open(out_path, "w", encoding="utf-8"), indent=1, ensure_ascii=False)
