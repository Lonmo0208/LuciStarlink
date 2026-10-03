#!/usr/bin/env bash
# 层一致性不变量：**编辑之后，每个被写过的 section 的 visible 层与 updating 层必须相同**。
#   客户端渲染和存档读 visible（命令里的 block=/sky=），引擎读写 updating（updBlock=/updSky=）。
#   两层的语义不同（SWMR：写者更新 updating，`updateVisible()` 才让它对读者可见），所以
#   「引擎内部对、玩家看到旧值」正是 visible 落后 —— 2026-09-29 用户截图的残留就是这个（docs/HANDOVER.md 10.42）。
# 用法：check-layers.sh <gate log> [...]          逐文件报告两层不一致的行数与前几行
#      check-layers.sh --selftest                 先证明它能抓到（拿一条人造的坏行）
set -uo pipefail
check_one() {
  local log=$1
  [ -f "$log" ] || { echo "  $log: 无此文件"; return; }
  awk '
    /LuciStarlink light / {
      sub(/^.*LuciStarlink /, "")
      total++
      b=$5; s=$6; ub=$8; us=$9
      sub("block=", "", b); sub("sky=", "", s); sub("updBlock=", "", ub); sub("updSky=", "", us)
      # 去初始化的 section（状态 N/U，2026-10-04 探针起带 bSt/sSt）读原始 updating 是 0、读语义层走
      # 高度图回退 —— 两层“不一致”是表示差异，不是残留（残留门的 2 格伪影）。真残留发生在 I/H 态。
      # 豁免按层分开：block 差异看 bSt，sky 差异看 sSt —— 一侧去初始化不掩盖另一侧的真残留。
      bSt = "I"; sSt = "I"
      for (i = 1; i <= NF; i++) {
        if ($i ~ /^bSt=/) bSt = substr($i, 5)
        if ($i ~ /^sSt=/) sSt = substr($i, 5)
      }
      bBad = (b != ub && bSt != "N" && bSt != "U")
      sBad = (s != us && sSt != "N" && sSt != "U")
      if (bBad || sBad) {
        bad++
        if (bad <= 5) printf "    x=%s y=%s z=%s visible(block/sky)=%s/%s updating=%s/%s\n", $2, $3, $4, b, s, ub, us
      } else if ((b != ub || s != us)) {
        skipped++
      }
    }
    END {
      printf "  %s: 读数 %d，两层不一致 %d%s\n", (bad ? "** 不一致 **" : "OK"), total, bad+0,
        (skipped ? "（另有 " skipped " 条为去初始化 section 的表示差异，已豁免）" : "")
    }' "$log"
}
if [ "${1:-}" = "--selftest" ]; then
  tmp=$(mktemp)
  printf 'LuciStarlink light 12, -30, -8 block=2 sky=15 raw=2 updBlock=0 updSky=15 sable=false\n' > "$tmp"
  printf 'LuciStarlink light 4, -37, -16 block=15 sky=0 raw=15 updBlock=15 updSky=0 sable=false\n' >> "$tmp"
  echo "--- selftest（第一行必须被判为不一致）---"
  check_one "$tmp"
  rm -f "$tmp"
  exit 0
fi
[ $# -gt 0 ] || { echo "用法: check-layers.sh <gate log> [...] | --selftest"; exit 2; }
for log in "$@"; do check_one "$log"; done
