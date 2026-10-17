"""Downloads every page of a few audio retailers' public Shopify product feeds (products.json) into a folder."""
import json, os, sys, time, urllib.request

out_dir = sys.argv[1]
os.makedirs(out_dir, exist_ok=True)
UA = "AudioGearAggregator/1.0 (personal desktop app)"
for shop in ["apos.audio", "hifigo.com", "www.linsoul.com", "shenzhenaudio.com", "www.headphones.com", "fosiaudio.com", "bloomaudio.com", "audio46.com", "hiby.com", "www.ifi-audio.com"]:
    pages = 0
    for page in range(1, 41):
        path = os.path.join(out_dir, f"{shop}-{page}.json")
        if os.path.exists(path):
            pages += 1
            continue
        try:
            req = urllib.request.Request(f"https://{shop}/products.json?limit=250&page={page}", headers={"User-Agent": UA})
            data = urllib.request.urlopen(req, timeout=60).read()
            products = json.loads(data)["products"]
        except Exception as e:
            print(f"{shop}: page {page} failed: {e}")
            break
        if not products:
            break
        with open(path, "wb") as f:
            f.write(data)
        pages += 1
        time.sleep(1)
    print(f"{shop}: {pages} pages")
