"""Followed TikTok accounts: does a profile page's own item_list request return videos logged out?"""
import time, json
from playwright.sync_api import sync_playwright
def note(t, m): print(f"::notice title={t}::{str(m).replace(chr(10),' ')[:800]}")
with sync_playwright() as p:
    br = p.chromium.launch()
    for user in ["madsroenning", "bibleproject", "norwegianchristian"]:
        for name, opts in (("desktop", {"viewport": {"width": 1280, "height": 900}, "user_agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0 Safari/537.36"}),):
            ctx = br.new_context(**opts, locale="nb-NO"); page = ctx.new_page()
            got = []
            def on_resp(r):
                if "/api/post/item_list" in r.url or "/api/creator/item_list" in r.url:
                    try:
                        d = r.json(); got.append((r.status, len(d.get("itemList") or []), [i.get("desc", "")[:30] for i in (d.get("itemList") or [])[:2]]))
                    except Exception as e: got.append((r.status, "nojson", str(e)[:60]))
            page.on("response", on_resp)
            try:
                page.goto(f"https://www.tiktok.com/@{user}", wait_until="domcontentloaded", timeout=40000)
                for _ in range(8):
                    time.sleep(1.5); page.mouse.wheel(0, 2000)
                links = page.evaluate("document.querySelectorAll('a[href*=\"/video/\"]').length")
                html = page.content()
                note(f"@{user} {name}", {"item_list": got, "links": links, "title": page.title()[:50], "rehydration_items": html.count('"itemStruct"')})
            except Exception as e: note(f"@{user} error", e)
            ctx.close()
    br.close()
