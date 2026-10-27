#!/usr/bin/env bash
# 下載速成／倉頡需要的資料到 app/src/main/assets（編譯前執行一次；GitHub Actions 會自動執行）：
#   cangjie5.base.dict.yaml  Rime 倉頡五代單字碼表（rime/rime-cangjie，LGPL）
#   freq.tsv                 由 rime-essay 的 essay.txt 取出的單字字頻（用來排候選字）
#   stroke.tsv               由 rime/rime-stroke 的 stroke.dict.yaml 整理出的單字筆劃碼（h橫 s豎 p撇 n點 z折）
set -euo pipefail
DEST="$(cd "$(dirname "$0")/.." && pwd)/app/src/main/assets"
mkdir -p "$DEST"
if [ -s "$DEST/cangjie5.base.dict.yaml" ] && [ -f "$DEST/freq.tsv" ] && [ -f "$DEST/stroke.tsv" ]; then
  echo "IME data already present"; exit 0
fi
TMP="$(mktemp -d)"

# 碼表：一定要成功
curl -fL --retry 3 -o "$DEST/cangjie5.base.dict.yaml" \
  "https://github.com/rime/rime-cangjie/raw/refs/heads/master/cangjie5.base.dict.yaml"
test "$(wc -l < "$DEST/cangjie5.base.dict.yaml")" -gt 1000

# 字頻：失敗也不讓編譯失敗（只是候選字順序較差）
got=0
for u in \
  "https://github.com/rime/rime-essay/raw/refs/heads/master/essay.txt" \
  "https://raw.githubusercontent.com/rime/rime-essay/e0160b8ef723a423e84ee52b53614ac3382f6d16/essay.txt"; do
  if curl -fL --retry 2 -o "$TMP/essay.txt" "$u"; then got=1; break; fi
done
: > "$DEST/freq.tsv"
if [ "$got" = 1 ]; then
  python3 - "$TMP/essay.txt" "$DEST/freq.tsv" <<'PY'
import sys
src, dst = sys.argv[1], sys.argv[2]
n = 0
with open(src, encoding="utf-8") as f, open(dst, "w", encoding="utf-8") as o:
    for line in f:
        p = line.rstrip("\n").split("\t")
        if len(p) >= 2 and len(p[0]) == 1 and p[1].isdigit():
            o.write(p[0] + "\t" + p[1] + "\n")
            n += 1
print("single-char frequencies:", n)
PY
else
  echo "WARNING: essay.txt download failed, candidates will not be frequency-sorted"
fi
# 筆劃碼表：失敗也不讓編譯失敗（筆劃輸入法會顯示「尚無碼表」，其他輸入法不受影響）
: > "$DEST/stroke.tsv"
if curl -fL --retry 2 -o "$TMP/stroke.dict.yaml" \
  "https://github.com/rime/rime-stroke/raw/refs/heads/master/stroke.dict.yaml"; then
  python3 - "$TMP/stroke.dict.yaml" "$DEST/stroke.tsv" <<'PY'
import re, sys
src, dst = sys.argv[1], sys.argv[2]
lines = open(src, encoding="utf-8").read().split("\n")
start = 0
for i, l in enumerate(lines[:200]):
    if l.strip() == "...":
        start = i + 1
        break
print("stroke.dict.yaml header:", [l for l in lines[:start] if l.strip()][:8])
print("first body lines:", lines[start:start + 5])

def parse(swapped):
    seen, out = set(), []
    for l in lines[start:]:
        if not l or l[0] == "#":
            continue
        p = l.split("\t") if "\t" in l else l.split()
        if len(p) < 2:
            continue
        ch, code = (p[1], p[0]) if swapped else (p[0], p[1])
        ch, code = ch.strip(), code.strip().lower()
        if len(ch) != 1:
            continue
        cp = ord(ch)
        # 只收常用區：CJK 基本區、擴充 A、相容漢字（擴充 B 以後的罕見字略過）
        if not (0x3400 <= cp <= 0x9FFF or 0xF900 <= cp <= 0xFAFF):
            continue
        if not re.fullmatch(r"[hspnz]+", code):
            continue
        if (ch, code) in seen:
            continue
        seen.add((ch, code))
        out.append(ch + "\t" + code)
    return out

out = parse(False)
if len(out) < 1000:
    out = parse(True)
if len(out) < 1000:
    print("WARNING: could not parse stroke.dict.yaml (entries:", len(out), ") - stroke input disabled")
    out = []
open(dst, "w", encoding="utf-8").write("\n".join(out))
print("stroke entries:", len(out))
PY
else
  echo "WARNING: stroke.dict.yaml download failed, stroke input disabled"
fi
rm -rf "$TMP"
ls -l "$DEST"
