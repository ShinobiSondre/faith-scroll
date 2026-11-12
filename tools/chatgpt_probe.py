"""Which TikTok /discover pages have Norwegian Christian videos? (headless Chromium, desktop)"""
import time
from playwright.sync_api import sync_playwright
def note(t, m): print(f"::notice title={t}::{str(m).replace(chr(10),' ')[:700]}")
READ = """(()=>{const o={};document.querySelectorAll('a[href*="/video/"]').forEach(a=>{const m=a.href.match(/@([^/?#]+)\\/video\\/(\\d{15,})/);if(!m)return;
const b=a.closest('[data-e2e]')||a.parentElement;let t=(a.getAttribute('title')||a.innerText||'')+' '+((b&&b.innerText)||'');const i=a.querySelector('img');if(i&&i.alt)t+=' '+i.alt;o[m[2]]=t.replace(/\\s+/g,' ').slice(0,120)});window.scrollBy(0,2500);return o})()"""
slugs = ["bibelvers", "bibelvers-på-norsk", "norske-bibelvers", "kristen-norsk", "norsk-kristen", "jesus-norsk", "kristne-sanger-norsk",
         "bønn-norsk", "kristen-tiktok-norge", "norwegian-christian", "jesus-norge", "lovsang-norsk", "dagens-bibelvers", "bible-verse-of-the-day", "christian-motivation"]
with sync_playwright() as p:
    br = p.chromium.launch()
    ctx = br.new_context(viewport={"width": 1280, "height": 900}, locale="nb-NO",
        user_agent="Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0 Safari/537.36")
    page = ctx.new_page()
    for s in slugs:
        try:
            page.goto("https://www.tiktok.com/discover/" + s, wait_until="domcontentloaded", timeout=40000)
            o = {}
            for _ in range(8):
                time.sleep(1.5)
                try: o = page.evaluate(READ)
                except Exception: pass
                if len(o) >= 15: break
            note(f"{s}: {len(o)} videos  (title: {page.title()[:40]})", list(o.values())[:4])
        except Exception as e: note(f"{s} error", e)
    br.close()
