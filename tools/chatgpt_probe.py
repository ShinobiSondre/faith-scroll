"""TikTok player in a frame: its messages (time, state), and the cookie banner (what 'Allow all' stores)."""
import time, json
from playwright.sync_api import sync_playwright
def note(t, m): print(f"::notice title={t}::{str(m).replace(chr(10),' ')[:900]}")
VID = "7706804780441685278"
SRC = f"https://www.tiktok.com/player/v1/{VID}?autoplay=1&loop=0&controls=0&progress_bar=1&play_button=0&volume_control=0&fullscreen_button=0&music_info=0&description=0&rel=0&native_context_menu=0&closed_caption=0"
PAGE = f"""<html><body style="margin:0;background:#000"><iframe id=p src="{SRC}" style="border:0;width:100vw;height:100vh" allow="autoplay"></iframe>
<script>window.msgs=[];window.addEventListener('message',e=>{{try{{window.msgs.push(JSON.stringify(e.data).slice(0,160))}}catch(x){{}}}});</script></body></html>"""
with sync_playwright() as p:
    br = p.chromium.launch(args=["--autoplay-policy=no-user-gesture-required"])
    ctx = br.new_context(**p.devices["Pixel 7"], locale="nb-NO"); page = ctx.new_page()
    page.route("https://faithscroll.app/", lambda r: r.fulfill(status=200, content_type="text/html", body=PAGE))
    page.goto("https://faithscroll.app/"); time.sleep(8)
    msgs = page.evaluate("window.msgs")
    kinds = {}
    for m in msgs:
        try: t = json.loads(m).get("type")
        except Exception: t = m[:30]
        kinds[t] = kinds.get(t, 0) + 1
    note("message types", kinds)
    note("samples", [m for m in msgs if "CurrentTime" in m or "State" in m][:4])
    f = [fr for fr in page.frames if "tiktok.com" in fr.url][0]
    DEEP = """(()=>{function walk(root,out){root.querySelectorAll('*').forEach(e=>{out.push(e);if(e.shadowRoot)walk(e.shadowRoot,out)});return out}
      const all=walk(document,[]);const btn=all.filter(e=>(e.tagName==='BUTTON'||e.getAttribute&&e.getAttribute('role')==='button')&&/allow|tillat|godta|accept|decline|avslå/i.test(e.textContent||''));
      return btn.map(b=>b.tagName+':'+(b.textContent||'').trim().slice(0,40))})()"""
    time.sleep(4)
    note("banner buttons", f.evaluate(DEEP))
    before = {c["name"] for c in ctx.cookies("https://www.tiktok.com")}
    clicked = f.evaluate("""(()=>{function walk(root,out){root.querySelectorAll('*').forEach(e=>{out.push(e);if(e.shadowRoot)walk(e.shadowRoot,out)});return out}
      const b=walk(document,[]).find(e=>(e.tagName==='BUTTON'||(e.getAttribute&&e.getAttribute('role')==='button'))&&/^(allow all|tillat alle|godta alle|accept all)$/i.test((e.textContent||'').trim()));
      if(b){b.click();return b.textContent.trim()}return null})()""")
    time.sleep(2)
    after = ctx.cookies("https://www.tiktok.com")
    note("clicked", clicked)
    note("new cookies", [(c["name"], c["value"][:300], c["domain"]) for c in after if c["name"] not in before])
    note("all cookie names", sorted(c["name"] for c in after))
    # seek test
    page.evaluate("document.getElementById('p').contentWindow.postMessage({type:'seekTo',value:20,'x-tiktok-player':true},'*')"); time.sleep(2)
    note("after seekTo 20", [m for m in page.evaluate("window.msgs") if "CurrentTime" in m][-1:])
    br.close()
