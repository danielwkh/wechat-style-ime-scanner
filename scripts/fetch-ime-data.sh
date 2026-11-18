#!/usr/bin/env bash
# 下載速成／倉頡需要的資料到 app/src/main/assets（編譯前執行一次；GitHub Actions 會自動執行）：
#   cangjie5.base.dict.yaml  Rime 倉頡五代單字碼表（rime/rime-cangjie，LGPL）
#   freq.tsv                 由 rime-essay 的 essay.txt 取出的單字字頻（用來排候選字）
#   stroke.tsv               由 rime/rime-stroke 的 stroke.dict.yaml 整理出的單字筆劃碼（h橫 s豎 p撇 n點 z折）
#   related.tsv              關聯詞表：jieba 詞典（MIT）轉繁，依 rime-essay 詞頻排序；每行「錨字 詞 詞 …」
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
# 關聯詞表：related.tsv 已隨版控提供（app/src/main/assets/related.tsv）。
# 只有在檔案不存在時才重新產生；產生失敗也不讓編譯失敗，程式會退回 Dict.kt 的手寫詞庫。
if [ ! -s "$DEST/related.tsv" ]; then
  echo "related.tsv not found, generating from jieba + rime-essay"
  if ! python3 -c "import opencc" 2>/dev/null; then
    python3 -m pip install --quiet --disable-pip-version-check opencc-python-reimplemented 2>/dev/null || true
  fi
  if ! curl -fL --retry 2 -o "$TMP/jieba_dict.txt" \
    "https://github.com/fxsjy/jieba/raw/refs/heads/master/jieba/dict.txt"; then
    echo "WARNING: jieba dict download failed, related words fall back to Dict.kt"
    : > "$DEST/related.tsv"
  elif [ ! -s "$TMP/essay.txt" ]; then
    echo "WARNING: no essay.txt, related word table skipped"
    : > "$DEST/related.tsv"
  else
    python3 - "$TMP/essay.txt" "$TMP/jieba_dict.txt" "$DEST/related.tsv" <<'PYGEN'
import sys
from collections import defaultdict

def cjk(ch):
    cp = ord(ch)
    return 0x3400 <= cp <= 0x9fff

essay, jieba, dst = sys.argv[1], sys.argv[2], sys.argv[3]
MIN_F, PER = 800, 24   # 詞頻門檻、每個錨字保留幾個詞

try:
    import opencc
    s2t = opencc.OpenCC("s2t")
    t2tw = opencc.OpenCC("t2tw")
except Exception:
    print("WARNING: opencc unavailable, related word table skipped")
    open(dst, "w").close()
    raise SystemExit(0)

# 詞頻：essay 用異體字（爲／裏／着），先正規化成台灣標準體（為／裡／著）
freq = {}
with open(essay, encoding="utf-8") as f:
    for line in f:
        p = line.rstrip("\n").split("\t")
        if len(p) == 2 and p[1].isdigit():
            w = t2tw.convert(p[0])
            freq[w] = max(freq.get(w, 0), int(p[1]))

best = {}
with open(jieba, encoding="utf-8") as f:
    for line in f:
        p = line.split()
        if len(p) < 2:
            continue
        w = p[0]
        # 只收 2–3 字純漢字詞，避免「你一、我個」這種跨界假詞
        if not (2 <= len(w) <= 3) or not all(cjk(c) for c in w):
            continue
        t = t2tw.convert(s2t.convert(w))
        if len(t) != len(w) or not all(cjk(c) for c in t):
            continue
        fv = freq.get(t, 0)
        if fv >= MIN_F and fv > best.get(t, -1):
            best[t] = fv

by_first = defaultdict(list)
for w, fv in best.items():
    by_first[w[0]].append((fv, w))

lines = []
for ch in sorted(by_first):
    lst = sorted(by_first[ch], key=lambda x: (-x[0], x[1]))[:PER]
    lines.append(ch + " " + " ".join(w for _, w in lst))
with open(dst, "w", encoding="utf-8") as o:
    o.write("\n".join(lines) + "\n")
print("related.tsv:", len(by_first), "anchors,", sum(len(v) for v in by_first.values()), "words")
PYGEN
  fi
fi
rm -rf "$TMP"
ls -l "$DEST"
