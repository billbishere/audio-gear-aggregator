"""Turns amp_specs.py output into a specs-only research file: gear rows with amp_power / output_impedance_ohm and
the pages each value came from (no copied text). Usage: write_specs.py <specs.json> <out research file> <yyyy-mm-dd>"""
import json, sys

specs, out, today = sys.argv[1:4]
items = [{"gear": i["gear"], "sources": i["sources"]} for i in json.load(open(specs, encoding="utf-8"))]
doc = {
    "title": "Output power and output impedance of headphone amps and DAC/amps",
    "gathered": today,
    "method": "For the headphone amps, DAC/amps, dongles and DAPs measured by Audio Science Review: power and output "
              "impedance stated in ASR's review text (measured), else in the manufacturer specs quoted by retailers "
              "(Apos Audio, HiFiGo, Linsoul, Shenzhen Audio, Headphones.com, Bloom Audio, Audio46) and the makers' own "
              "stores (Fosi Audio, HiBy, iFi). Models are matched by brand and exact model name, versions kept apart. "
              "Power is SE/BALANCED:load ohms=milliwatts. Specs only: importing fills empty fields and changes nothing else.",
    "items": items,
}
with open(out, "w", encoding="utf-8", newline="\n") as f:
    json.dump(doc, f, indent=1, ensure_ascii=False)
    f.write("\n")
print(f"{len(items)} items -> {out}")
