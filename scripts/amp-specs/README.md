# Amp output power and output impedance

Builds `research/<date>-amp-specs.json`, a specs-only research file that fills `amp_power` and
`output_impedance_ohm` for the headphone amps and DAC/amps in `research/2026-10-asr-amps-dacs.json` (used by the
Pairing screen). Values come from Audio Science Review's review text (measured) or, failing that, manufacturer specs
quoted in retailers' public Shopify product feeds. Installed apps import it on their next update; it only fills empty
fields.

```bash
python fetch_reviews.py ../../research/2026-10-asr-amps-dacs.json build/reviews
python fetch_shops.py build/shops
python amp_specs.py ../../research/2026-10-asr-amps-dacs.json build/shops build/reviews build/specs.json
python write_specs.py build/specs.json ../../research/<date>-amp-specs.json <date>
```

`specs.json` keeps the source sentence behind every value (`evidence`): spot-check a sample before publishing. Use a
new file name for each run, because apps apply each research file once, by name.
