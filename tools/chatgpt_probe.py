"""Plain HTTP (like the phone's OkHttp): do TikTok /discover pages list video links? Does oEmbed work?"""
import re, json, urllib.request
def note(t, m): print(f"::notice title={t}::{str(m).replace(chr(10),' ')[:900]}")
UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0 Safari/537.36"
def get(u):
    r = urllib.request.Request(u, headers={"User-Agent": UA, "Accept-Language": "en-US,en;q=0.9"})
    return urllib.request.urlopen(r, timeout=20).read().decode("utf-8", "replace")
first = None
for slug in ["bible-verse-of-the-day", "bible-verses-for-encouragement", "christian-motivation", "bible-verses-about-strength", "bibelvers", "kristen", "jesus-loves-you", "god-is-good"]:
    try:
        h = get("https://www.tiktok.com/discover/" + slug)
        ids = list(dict.fromkeys(re.findall(r'/@([\w.\-]+)/video/(\d{15,})', h)))
        js = re.findall(r'"desc":"([^"]{0,80})', h)[:3]
        first = first or (ids[0] if ids else None)
        note(f"discover {slug}", {"len": len(h), "ids": len(ids), "sample": ids[:3], "desc": js, "rehydration": "__UNIVERSAL_DATA_FOR_REHYDRATION__" in h})
    except Exception as e: note(f"discover {slug} error", e)
if first:
    u, i = first
    try: note("oembed", json.loads(get(f"https://www.tiktok.com/oembed?url=https://www.tiktok.com/@{u}/video/{i}"))["title"][:120])
    except Exception as e: note("oembed error", e)
    try: h = get(f"https://www.tiktok.com/embed/v2/{i}"); note("embed v2", {"len": len(h), "video": "playAddr" in h or "<video" in h})
    except Exception as e: note("embed error", e)
