"""Does ChatGPT answer FaithScroll's kind of question from a ?q= link? (headless Chromium ~ Android WebView)"""
import time, urllib.parse
from playwright.sync_api import sync_playwright
def note(t, m): print(f"::notice title={t}::{str(m).replace(chr(10),' ')[:900]}")
STATE = """(()=>{const a=[...document.querySelectorAll('[data-message-role="assistant"],[data-message-author-role="assistant"]')].map(e=>e.innerText);
const c=document.querySelector('#prompt-textarea')||document.querySelector('textarea');
const dl=[...document.querySelectorAll('[role="dialog"],[aria-modal="true"]')].map(e=>(e.innerText||'').replace(/\\s+/g,' ').slice(0,160)).join(' | ');
return {a:a, users:document.querySelectorAll('[data-message-role="user"],[data-message-author-role="user"]').length, pending:c?(c.innerText||c.value||'').trim().length:-1,
busy:!!document.querySelector('[data-message-streaming],[data-testid="stop-button"]'), url:location.pathname, dialog:dl, tail:document.body.innerText.slice(-200)}})()"""
line = "[3:{s:02d}] og Jesus sa til dem at de skulle gå ut i hele verden og forkynne evangeliet for alle mennesker, Markus 16,15"
def prompt(n_chars, transcript=True):
    t = "\n".join(line.format(s=i % 60) for i in range(200))[:n_chars] if transcript else ""
    q = "YouTube-video: https://www.youtube.com/watch?v=dQw4w9WgXcQ&t=200s («Vitnesbyrd» av Rasmus Hansen). Pauset ved 3:20.\n"
    if t: q += "Det som blir sagt rundt dette øyeblikket (teksting med tidspunkt):\n" + t + "\n\n"
    return q + "Hva mener de ved 3:20? Forklar kort på norsk, ut fra sammenhengen."
cases = [("short 800", prompt(800), {}), ("full 4500", prompt(4500), {}), ("full 4500 + hints=search", prompt(4500), {"hints": "search"}),
         ("no transcript + hints=search", prompt(0, False), {"hints": "search"}), ("2500", prompt(2500), {})]
with sync_playwright() as p:
    br = p.chromium.launch()
    for name, q, extra in cases:
        url = "https://chatgpt.com/?" + urllib.parse.urlencode({**extra, "q": q})
        ctx = br.new_context(**p.devices["Pixel 7"], locale="nb-NO"); page = ctx.new_page()
        try:
            page.goto(url, wait_until="domcontentloaded", timeout=60000)
            st = None; t0 = time.time(); first = None
            for _ in range(45):
                time.sleep(1)
                try: st = page.evaluate(STATE)
                except Exception: continue
                if st["a"] and first is None: first = time.time() - t0
                if st["a"] and not st["busy"] and len(st["a"][-1]) > 20: break
            note(f"{name}: q={len(q)} url={len(url)}", {"answered": bool(st and st['a']), "first_s": first, "users": st and st["users"], "pending": st and st["pending"],
                 "path": st and st["url"], "dialog": st and st["dialog"], "a": st and (st["a"][-1][:120] if st["a"] else ""), "tail": st and not st["a"] and st["tail"]})
        except Exception as e: note(f"{name} error", e)
        ctx.close()
    br.close()
