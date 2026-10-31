"""Builds app/src/main/assets/bible_no_nt.json.gz from eBible.org's noblb_vpl.zip:
En Levende Bok – Det Nye Testamentet (Biblica Open, CC BY-SA 4.0), modern Norwegian bokmål.
Output: {"39": [[verse1, verse2, ...] per chapter], ...} with book indexes 39 (Matthew) – 65 (Revelation)."""
import gzip, json, re, sys, zipfile

CODES = ["MAT","MRK","LUK","JHN","ACT","ROM","1CO","2CO","GAL","EPH","PHP","COL","1TH","2TH","1TI","2TI","TIT","PHM",
         "HEB","JAS","1PE","2PE","1JN","2JN","3JN","JUD","REV"]
zf = zipfile.ZipFile(sys.argv[1])
name = next(n for n in zf.namelist() if n.endswith("_vpl.txt") or n.endswith(".txt"))
books = {}
pat = re.compile(r"^([1-3A-Z]{3})\s+(\d+):(\d+)\s+(.*)$")
for line in zf.read(name).decode("utf-8-sig").splitlines():
    m = pat.match(line.strip())
    if not m or m.group(1) not in CODES: continue
    b = 39 + CODES.index(m.group(1)); c = int(m.group(2)); v = int(m.group(3))
    text = re.sub(r"\s+", " ", m.group(4)).strip()
    chs = books.setdefault(str(b), [])
    while len(chs) < c: chs.append([])
    vs = chs[c - 1]
    while len(vs) < v: vs.append("")
    vs[v - 1] = text
assert len(books) >= 20, f"only {len(books)} books parsed from {name}"
print("books:", len(books), "John 3:16:", books["42"][2][15])
with gzip.open(sys.argv[2], "wt", encoding="utf-8") as f:
    json.dump(books, f, ensure_ascii=False, separators=(",", ":"))
