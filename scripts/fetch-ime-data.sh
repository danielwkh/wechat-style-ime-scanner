#!/usr/bin/env bash
# 下載速成／倉頡需要的資料到 app/src/main/assets（編譯前執行一次；GitHub Actions 會自動執行）：
#   cangjie5.base.dict.yaml  Rime 倉頡五代單字碼表（rime/rime-cangjie，LGPL）
#   freq.tsv                 由 rime-essay 的 essay.txt 取出的單字字頻（用來排候選字）
set -euo pipefail
DEST="$(cd "$(dirname "$0")/.." && pwd)/app/src/main/assets"
mkdir -p "$DEST"
if [ -s "$DEST/cangjie5.base.dict.yaml" ] && [ -f "$DEST/freq.tsv" ]; then
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
rm -rf "$TMP"
ls -l "$DEST"
