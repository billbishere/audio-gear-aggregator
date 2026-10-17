"""Downloads the ASR review pages of the headphone amps and DAC/amps in the ASR research file, one every 1.5 s.
Usage: fetch_reviews.py <research/...asr-amps-dacs.json> <out dir>"""
import json, os, re, sys, time, urllib.request

research, out_dir = sys.argv[1:3]
os.makedirs(out_dir, exist_ok=True)
items = [i for i in json.load(open(research, encoding="utf-8"))["items"]
         if i["gear"]["type"] in ("AMP", "AMP_DAC") and "speaker-amp" not in i["gear"]["tags"]]
for it in items:
    for t in it["threads"]:
        path = os.path.join(out_dir, re.sub(r"[^\w.-]+", "_", t["url"].split("threads/")[-1]).strip("_")[:120] + ".html")
        if os.path.exists(path) and os.path.getsize(path) > 10000:
            continue
        req = urllib.request.Request(t["url"], headers={"User-Agent": "AudioGearAggregator/1.0 (personal desktop app)"})
        try:
            data = urllib.request.urlopen(req, timeout=60).read()
        except Exception as e:
            print("failed:", t["url"], e)
            continue
        with open(path, "wb") as f:
            f.write(data)
        time.sleep(1.5)
