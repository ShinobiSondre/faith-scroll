"""Can we get a TikTok's own video file (to play it in our player, like YouTube)? Plain HTTP like the phone."""
import re, json, http.cookiejar, urllib.request
def note(t, m): print(f"::notice title={t}::{str(m).replace(chr(10),' ')[:900]}")
UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0 Safari/537.36"
VIDS = [("alisoncarolena", "7706804780441685278"), ("prettygirlslive4jesus", "7699185313272450335"), ("faith.minutes", "7699501063505726738")]
for author, vid in VIDS:
    jar = http.cookiejar.CookieJar()
    op = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(jar))
    def get(u, headers=None, rng=False):
        h = {"User-Agent": UA, "Accept-Language": "en-US,en;q=0.9", "Referer": "https://www.tiktok.com/"}
        if rng: h["Range"] = "bytes=0-2047"
        h.update(headers or {})
        r = op.open(urllib.request.Request(u, headers=h), timeout=20)
        return r.status, r.headers.get("Content-Type"), r.read()
    try:
        st, ct, body = get(f"https://www.tiktok.com/@{author}/video/{vid}")
        html = body.decode("utf-8", "replace")
        m = re.search(r'<script id="__UNIVERSAL_DATA_FOR_REHYDRATION__"[^>]*>(.*?)</script>', html, re.S)
        if not m: note(f"{vid[-4:]} page", {"status": st, "len": len(html), "rehydration": False}); continue
        d = json.loads(m.group(1))
        item = d.get("__DEFAULT_SCOPE__", {}).get("webapp.video-detail", {}).get("itemInfo", {}).get("itemStruct", {})
        v = item.get("video", {})
        play = v.get("playAddr") or ""
        down = v.get("downloadAddr") or ""
        br = [(b.get("GearName"), b.get("PlayAddr", {}).get("UrlList", [""])[0][:60]) for b in v.get("bitrateInfo", [])][:3]
        note(f"{vid[-4:]} page", {"status": st, "desc": item.get("desc", "")[:60], "dur": v.get("duration"), "play": play[:90], "download": bool(down),
             "bitrates": br, "cookies": sorted(c.name for c in jar)})
        for name, url in (("playAddr", play), ("downloadAddr", down)):
            if not url: continue
            try:
                s2, c2, b2 = get(url, rng=True)
                note(f"{vid[-4:]} {name} with cookies", {"status": s2, "type": c2, "bytes": len(b2), "mp4": b2[4:8] == b"ftyp"})
            except Exception as e: note(f"{vid[-4:]} {name} with cookies error", str(e)[:200])
            try:
                r = urllib.request.urlopen(urllib.request.Request(url, headers={"User-Agent": UA, "Range": "bytes=0-2047", "Referer": "https://www.tiktok.com/"}), timeout=20)
                note(f"{vid[-4:]} {name} WITHOUT cookies", {"status": r.status, "bytes": len(r.read())})
            except Exception as e: note(f"{vid[-4:]} {name} WITHOUT cookies error", str(e)[:200])
    except Exception as e: note(f"{vid[-4:]} error", str(e)[:300])
