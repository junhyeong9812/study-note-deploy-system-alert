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

# 보냈으면 0, 못 보냈으면 1 — 웹훅이 4xx·5xx 로 답한 것도 실패다(다음 실행에 다시 보낸다)
send_discord() {
  if [ -z "${DISCORD_WEBHOOK_URL:-}" ]; then log "discord 미설정 — 보내지 않음: $1"; return 0; fi
  local payload code
  payload="$(printf '{"content": "%s"}' "$(printf '%s' "$1" | sed 's/\\/\\\\/g; s/"/\\"/g')")"
  code="$(curl -s -m 10 -o /dev/null -w '%{http_code}' -H 'Content-Type: application/json' -d "$payload" "$DISCORD_WEBHOOK_URL" 2>/dev/null)"
  case "$code" in
    2??) return 0 ;;
    *) log "discord 발송 실패(응답 ${code:-없음}): $1"; return 1 ;;
  esac
}

send_event() {   # 집이 다시 보일 때만 부른다 — 끊김 기록을 알림 서버에도 남긴다(실패해도 outages.log 에는 남아 있다)
  [ -n "${ALERT_SECRET:-}" ] || return 0
  local code
  code="$(curl -s -m 10 -o /dev/null -w '%{http_code}' -H 'Content-Type: application/json' -H "X-Alert-Secret: $ALERT_SECRET" \
    -d "$(printf '{"source":"edge","kind":"%s","severity":"info","key":"%s","title":"%s","detail":"%s"}' "$1" "$2" "$3" "$4")" \
    "$HOME_TARGET/api/ops/alert-events" 2>/dev/null)"
  case "$code" in 2??) ;; *) log "사건 전달 실패(응답 ${code:-없음}): $3" ;; esac
}

probe() { [ "$(curl -s -m 10 -o /dev/null -w '%{http_code}' "$1" 2>/dev/null)" = "200" ]; }

# 알림 서버가 감시를 하고 있는가 — 200 이어도 "standby"(대기 자리)면 아무도 감시하지 않는 것이다.
# 배포 전환 직후 30초쯤은 standby 가 보일 수 있지만 3번 연속 기준에 걸리지 않는다.
alert_watching() {
  local body
  body="$(curl -s -m 10 -w '\n%{http_code}' "$HOME_TARGET/api/ops/alert-health" 2>/dev/null)"
  [ "$(printf '%s' "$body" | tail -n 1)" = "200" ] && ! printf '%s' "$body" | grep -q '"status":"standby"'
}

# check <이름> <사람이 읽을 이름> <성공 여부(0|1)> <알림 보류(0|1)>
# 보류: 실패 횟수는 세되 "안 보임" 알림은 미룬다(같은 원인의 알림이 겹치지 않게). 보류가 풀려도 계속 실패면 그때 보낸다.
check() {
  local name="$1" label="$2" healthy="$3" hold="$4" state="$STATE_DIR/$1.state" failures=0 down_since=0 alerted=0
  # shellcheck disable=SC1090
  [ -r "$state" ] && . "$state"
  if [ "$healthy" = "1" ]; then
    if [ "$failures" -gt 0 ]; then
      local seconds=$((now - down_since))
      echo "$(stamp "$down_since") $(stamp "$now") ${seconds}s $name" >> "$STATE_DIR/outages.log"   # 짧은 끊김도 전부 남긴다
      if [ "$alerted" = "1" ]; then
        # 복구 알림을 못 보냈으면 적어 두고 다음 실행에 다시 보낸다(상태는 정상으로 되돌린다)
        local recovered="[집 밖 감시] 복구: ${label} — 끊긴 시간 $(readable "$seconds")"
        send_discord "$recovered" || printf '%s\n' "$recovered" > "$STATE_DIR/$name.pending"
        send_event "outage_recovered" "edge-outage:$name" "밖에서 본 ${label} 복구" "끊긴 시간 $(readable "$seconds") ($(stamp "$down_since") ~ $(stamp "$now"))"
      fi
    fi
    printf 'failures=0\ndown_since=0\nalerted=0\n' > "$state"
    return 0
  fi
  failures=$((failures + 1))
  [ "$failures" -eq 1 ] && down_since="$now"
  if [ "$failures" -ge "$ALERT_AFTER" ] && [ "$alerted" = "0" ] && [ "$hold" = "0" ]; then
    # 보낸 것이 확인됐을 때만 "알렸다"고 적는다 — 못 보냈으면 다음 실행에 다시 보낸다
    if send_discord "[집 밖 감시] 밖에서 안 보임: ${label} ($(readable $((now - down_since + 60)))째)"; then alerted=1; fi
  fi
  printf 'failures=%s\ndown_since=%s\nalerted=%s\n' "$failures" "$down_since" "$alerted" > "$state"
}

# 지난 실행에 못 보낸 복구 알림부터 다시 보낸다
for pending in "$STATE_DIR"/*.pending; do
  [ -f "$pending" ] || continue
  send_discord "$(cat "$pending")" && rm -f "$pending"
done

# 두 경로를 매번 따로 확인한다. 사이트가 안 보이는 동안에는 알림 서버 쪽 "안 보임" 알림만 미룬다 —
# 집 전체가 끊기면 둘 다 실패하므로 알림은 사이트 것 하나면 된다. 사이트가 돌아왔는데도 알림 서버가 안 보이면 그때 알린다.
if probe "$HOME_TARGET/"; then site_ok=1; else site_ok=0; fi
if alert_watching; then alert_ok=1; else alert_ok=0; fi
check site "사이트" "$site_ok" 0
check alert "알림 서버" "$alert_ok" "$((1 - site_ok))"

# 로그가 끝없이 자라지 않게 — 최근 5000줄만 남긴다
for file in watchdog.log outages.log; do
  [ -f "$STATE_DIR/$file" ] && [ "$(wc -l < "$STATE_DIR/$file")" -gt 6000 ] && tail -n 5000 "$STATE_DIR/$file" > "$STATE_DIR/$file.tmp" && mv "$STATE_DIR/$file.tmp" "$STATE_DIR/$file"
done
exit 0
