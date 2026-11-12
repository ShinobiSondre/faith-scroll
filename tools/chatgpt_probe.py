"""Can TikTok's hashtag / search pages be read logged-out (to find Bible videos)? Headless Chromium."""
import time, json, urllib.parse
from playwright.sync_api import sync_playwright
def note(t, m): print(f"::notice title={t}::{str(m).replace(chr(10),' ')[:900]}")
LINKS = """(()=>{const s=new Set();document.querySelectorAll('a[href*="/video/"]').forEach(a=>s.add(a.href.split('?')[0]));
return {n:s.size, links:[...s].slice(0,6), title:document.title, body:document.body.innerText.slice(0,160)}})()"""
pages = ["https://www.tiktok.com/tag/bibleverse", "https://www.tiktok.com/tag/bibelvers", "https://www.tiktok.com/tag/christiantiktok",
         "https://www.tiktok.com/search/video?q=bible%20verse", "https://www.tiktok.com/discover/bible-verse-of-the-day", "https://www.tiktok.com/@bibleproject"]
with sync_playwright() as p:
    br = p.chromium.launch()
    for ua_name, opts in (("desktop", {"viewport": {"width": 1280, "height": 900}, "user_agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0 Safari/537.36"}),
                          ("mobile", p.devices["Pixel 7"])):
        for url in pages:
            ctx = br.new_context(**opts, locale="en-US"); page = ctx.new_page()
            api = []
            page.on("response", lambda r: api.append(r.url.split('?')[0]) if ("/api/" in r.url and r.status == 200) else None)
            try:
                page.goto(url, wait_until="domcontentloaded", timeout=45000)
                st = None
                for _ in range(12):
                    time.sleep(1.5)
                    try: page.mouse.wheel(0, 2500)
                    except Exception: pass
                    try: st = page.evaluate(LINKS)
                    except Exception: continue
                    if st["n"] >= 12: break
                note(f"{ua_name} {url.split('tiktok.com')[1]}", {**(st or {}), "api": sorted(set(api))[:8]})
            except Exception as e: note(f"{ua_name} {url} error", e)
            ctx.close()
    # Bing as a fallback way to find TikTok video links.
    ctx = br.new_context(locale="en-US"); page = ctx.new_page()
    for q in ["site:tiktok.com bible verse video", "site:tiktok.com bibelvers"]:
        try:
            page.goto("https://www.bing.com/search?q=" + urllib.parse.quote(q), timeout=30000); time.sleep(2)
            st = page.evaluate("""(()=>{const s=new Set();document.querySelectorAll('a[href*="tiktok.com/@"]').forEach(a=>{if(/\\/video\\/\\d+/.test(a.href))s.add(a.href.split('?')[0])});return {n:s.size,links:[...s].slice(0,5)}})()""")
            note(f"bing {q}", st)
        except Exception as e: note(f"bing {q} error", e)
    br.close()
