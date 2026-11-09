#!/bin/bash
# 데모용 갤러리 채우기 (에뮬레이터 또는 USB로 연결한 폰)
#
# 사용법:
#   ./scripts/load_demo_photos.sh ~/Downloads/coco_photos
#
# 폴더 안의 1~8번 사진(1.jpg, 2.png … 8.webp 처럼 이름이 숫자)을 기기의 카메라 폴더(DCIM/Camera)에 한 장씩 넣어요(복제 없음).
# - 1~7번: 2026-11-09 11:30부터 1시간씩 앞 시각 → 갤러리에서 "Today"(11월 9일) 묶음.
# - 8번(손주 사진): 2026-10-20 15:00 → 갤러리 맨 아래 "Tue, Oct 20" 묶음.
#   COCO는 이 날짜("Oct 20")로 손주 사진을 찾아요(app/src/main/assets/steps/GAL_RECENT.json).
# - 모든 사진은 JPG로 바꿔서 넣어요(png, webp, jpg 모두 가능).
set -euo pipefail

DIR="${1:?사진 폴더 경로를 넣어 주세요. 예: ./scripts/load_demo_photos.sh ~/Downloads/coco_photos}"
ADB="${ADB:-$HOME/Library/Android/sdk/platform-tools/adb}"
CAMERA="/sdcard/DCIM/Camera"
START="202611091130"       # 1번 사진 시각. 2~7번은 1시간씩 앞이에요.
TARGET_DATE="202610201500" # 손주 사진(8번) 시각. 바꾸면 GAL_RECENT.json의 "Oct 20"도 바꿔야 해요.

"$ADB" get-state >/dev/null || { echo "기기가 연결되어 있지 않아요"; exit 1; }

# 예전에 넣은 사진을 지워요. 사진 목록(MediaStore)에서도 지워야 갤러리에 빈칸이 안 남아요.
"$ADB" shell "content delete --uri content://media/external/images/media --where \"_display_name LIKE 'coco_%' OR _display_name LIKE 'James_%'\"" >/dev/null 2>&1 || true
"$ADB" shell "mkdir -p $CAMERA && rm -f $CAMERA/coco_* $CAMERA/James_*"

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

find_photo() { find "$DIR" -maxdepth 1 -type f -iname "$1.*" | head -1; }

push_one() { # $1=원본, $2=기기 파일 이름(.jpg), $3=YYYYMMDDhhmm
  sips -s format jpeg "$1" --out "$TMP/$2" >/dev/null
  touch -t "$3" "$TMP/$2"
  "$ADB" push "$TMP/$2" "$CAMERA/$2" >/dev/null 2>&1 || { echo "보내기 실패: $2"; exit 1; }
  "$ADB" shell "touch -t $3 $CAMERA/$2"
  "$ADB" shell "am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d file://$CAMERA/$2" >/dev/null
  rm -f "$TMP/$2"
}

for i in 1 2 3 4 5 6 7; do
  src="$(find_photo "$i")"
  [ -z "$src" ] && { echo "  ${i}번 사진이 없어요(건너뜀)"; continue; }
  when="$(date -j -v-$((i - 1))H -f %Y%m%d%H%M "$START" +%Y%m%d%H%M)"
  push_one "$src" "coco_$i.jpg" "$when"
  echo "  ${i}번 → ${when:0:4}-${when:4:2}-${when:6:2} ${when:8:2}:${when:10:2}"
done

src="$(find_photo 8)"
if [ -n "$src" ]; then
  push_one "$src" "coco_grandchild.jpg" "$TARGET_DATE"
  echo "  8번(손주) → 2026-10-20 15:00, 맨 아래"
else
  echo "  8번(손주) 사진이 없어요!"
fi

echo "완료. Google 포토(또는 갤러리)를 다시 열면 보여요."
