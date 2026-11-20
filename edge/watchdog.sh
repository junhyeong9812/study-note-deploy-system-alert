#!/usr/bin/env bash
# 집 밖의 감시자 — 엣지 서버에서 1분마다 실행한다(예약 작업).
# 알림 서버는 집 안에 있어서, 집 전체(전원·회선·공유기)나 알림 서버 자신이 죽으면 스스로 알릴 수 없다.
# 그래서 장애 원인이 겹치지 않는 곳(엣지)에서 사이트와 알림 서버를 직접 확인하고, 안 보이면 여기서 바로 알린다.
#
# 설정 파일(값은 이 서버에만 둔다 — 저장소에 넣지 않는다): WATCHDOG_CONFIG (기본 /etc/alert-watchdog.env)
#   HOME_TARGET=http://<집 주소>:<front 포트>     필수
#   DISCORD_WEBHOOK_URL=...                       비어 있으면 보내지 않고 로그에만 남긴다
#   ALERT_SECRET=...                              복구 뒤 알림 서버에 사건으로 남길 때 쓴다(선택)
#   ALERT_AFTER=3                                 연속 실패 몇 번에 알릴지(1분 주기 → 3분)
set -u
CONFIG="${WATCHDOG_CONFIG:-/etc/alert-watchdog.env}"
STATE_DIR="${WATCHDOG_STATE_DIR:-/var/lib/alert-watchdog}"
# shellcheck disable=SC1090
[ -r "$CONFIG" ] && . "$CONFIG"
: "${HOME_TARGET:?HOME_TARGET 이 설정되지 않았다($CONFIG)}"
ALERT_AFTER="${ALERT_AFTER:-3}"
mkdir -p "$STATE_DIR"
now="$(date +%s)"
stamp() { date -u -d "@$1" +%Y-%m-%dT%H:%M:%SZ; }
log() { echo "$(stamp "$now") $*" >> "$STATE_DIR/watchdog.log"; }

readable() {   # 초 → "N분 M초"
  if [ "$1" -lt 60 ]; then echo "${1}초"; else echo "$(($1 / 60))분 $(($1 % 60))초"; fi
}

send_discord() {   # 실패해도 스크립트는 계속된다
  if [ -z "${DISCORD_WEBHOOK_URL:-}" ]; then log "discord 미설정 — 보내지 않음: $1"; return 0; fi
  local payload
  payload="$(printf '{"content": "%s"}' "$(printf '%s' "$1" | sed 's/\\/\\\\/g; s/"/\\"/g')")"
  curl -sS -m 10 -o /dev/null -H 'Content-Type: application/json' -d "$payload" "$DISCORD_WEBHOOK_URL" 2>>"$STATE_DIR/watchdog.log" \
    || log "discord 발송 실패: $1"
}

send_event() {   # 집이 다시 보일 때만 부른다 — 끊김 기록을 알림 서버에도 남긴다
  [ -n "${ALERT_SECRET:-}" ] || return 0
  curl -sS -m 10 -o /dev/null -H 'Content-Type: application/json' -H "X-Alert-Secret: $ALERT_SECRET" \
    -d "$(printf '{"source":"edge","kind":"%s","severity":"info","key":"%s","title":"%s","detail":"%s"}' "$1" "$2" "$3" "$4")" \
    "$HOME_TARGET/api/ops/alert-events" 2>>"$STATE_DIR/watchdog.log" || log "사건 전달 실패: $3"
}

probe() { [ "$(curl -s -m 10 -o /dev/null -w '%{http_code}' "$1" 2>/dev/null)" = "200" ]; }

# check <이름> <사람이 읽을 이름> <성공 여부(0|1)>
check() {
  local name="$1" label="$2" healthy="$3" state="$STATE_DIR/$1.state" failures=0 down_since=0 alerted=0
  # shellcheck disable=SC1090
  [ -r "$state" ] && . "$state"
  if [ "$healthy" = "1" ]; then
    if [ "$failures" -gt 0 ]; then
      local seconds=$((now - down_since))
      echo "$(stamp "$down_since") $(stamp "$now") ${seconds}s $name" >> "$STATE_DIR/outages.log"   # 짧은 끊김도 전부 남긴다
      if [ "$alerted" = "1" ]; then
        send_discord "[집 밖 감시] 복구: ${label} — 끊긴 시간 $(readable "$seconds")"
        send_event "outage_recovered" "edge-outage:$name" "밖에서 본 ${label} 복구" "끊긴 시간 $(readable "$seconds") ($(stamp "$down_since") ~ $(stamp "$now"))"
      fi
    fi
    printf 'failures=0\ndown_since=0\nalerted=0\n' > "$state"
    return 0
  fi
  failures=$((failures + 1))
  [ "$failures" -eq 1 ] && down_since="$now"
  if [ "$failures" -ge "$ALERT_AFTER" ] && [ "$alerted" = "0" ]; then
    send_discord "[집 밖 감시] 밖에서 안 보임: ${label} ($(readable $((now - down_since + 60)))째)"
    alerted=1
  fi
  printf 'failures=%s\ndown_since=%s\nalerted=%s\n' "$failures" "$down_since" "$alerted" > "$state"
}

if probe "$HOME_TARGET/"; then
  check site "사이트" 1
  # 사이트가 보일 때만 알림 서버를 따로 본다 — 사이트가 안 보이면 알림 서버 확인도 같이 실패하므로 알림을 겹치지 않는다
  if probe "$HOME_TARGET/api/ops/alert-health"; then check alert "알림 서버" 1; else check alert "알림 서버" 0; fi
else
  check site "사이트" 0
fi

# 로그가 끝없이 자라지 않게 — 최근 5000줄만 남긴다
for file in watchdog.log outages.log; do
  [ -f "$STATE_DIR/$file" ] && [ "$(wc -l < "$STATE_DIR/$file")" -gt 6000 ] && tail -n 5000 "$STATE_DIR/$file" > "$STATE_DIR/$file.tmp" && mv "$STATE_DIR/$file.tmp" "$STATE_DIR/$file"
done
exit 0
