"""Which way of embedding a TikTok actually plays? (headless Chromium, phone)"""
import time
from playwright.sync_api import sync_playwright
def note(t, m): print(f"::notice title={t}::{str(m).replace(chr(10),' ')[:600]}")
IDS = ["7699185313272450335", "7706804780441685278", "7701465373933194527"]
PARAMS = "autoplay=1&loop=1&controls=0&progress_bar=1&play_button=0&volume_control=0&fullscreen_button=0&music_info=0&description=0&rel=0&native_context_menu=0&closed_caption=0"
STATE = """(()=>{const v=document.querySelector('video');const t=document.body?document.body.innerText.slice(0,120):'';
return {video:!!v, t:v?v.currentTime:-1, ready:v?v.readyState:-1, src:v?(v.currentSrc||'').slice(0,60):'', text:t}})()"""
def frame_state(page):
    out = []
    for f in page.frames:
        try: out.append(f.evaluate(STATE))
        except Exception: pass
    return out
with sync_playwright() as p:
    br = p.chromium.launch(args=["--autoplay-policy=no-user-gesture-required"])
    for vid in IDS[:2]:
        for name, mode, url in [
            ("player top-level", "top", f"https://www.tiktok.com/player/v1/{vid}?{PARAMS}"),
            ("embed/v2 top-level", "top", f"https://www.tiktok.com/embed/v2/{vid}"),
            ("player in iframe @example.com", "iframe", f"https://www.tiktok.com/player/v1/{vid}?{PARAMS}"),
            ("player in iframe @faithscroll.app", "iframe2", f"https://www.tiktok.com/player/v1/{vid}?{PARAMS}"),
        ]:
            ctx = br.new_context(**p.devices["Pixel 7"], locale="nb-NO"); page = ctx.new_page()
            try:
                if mode == "top": page.goto(url, wait_until="domcontentloaded", timeout=40000)
                else:
                    host = "https://example.com/" if mode == "iframe" else "https://faithscroll.app/"
                    page.route(host, lambda r: r.fulfill(status=200, content_type="text/html",
                        body=f'<html><body style="margin:0;background:#000"><iframe src="{url}" style="border:0;width:100vw;height:100vh" allow="autoplay; fullscreen"></iframe></body></html>'))
                    page.goto(host, timeout=40000)
                time.sleep(8)
                note(f"{vid[-4:]} {name}", frame_state(page))
            except Exception as e: note(f"{vid[-4:]} {name} error", e)
            ctx.close()
    br.close()
