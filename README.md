# study-note-deploy-system-alert

알림 서버 — 서비스가 죽거나, 의존 서비스가 안 되거나, 배포가 실패하면 사람에게 알린다. Kotlin · Spring Boot.

```
감시 대상(front · backend · llm · 배포 시스템)  ◀── 30초마다 확인 ── 알림 서버 ──▶ 디스코드 → (실패 시) 슬랙 → (실패 시) 메일
다른 서버(ci-cd 등) ── POST /events ──────────────────────────────▶    │
집 밖의 감시자(엣지) ── 알림 서버가 죽으면 직접 알린다                    └── 매일 09:00 요약
```

## 판정

- 30초마다 모든 대상을 동시에 확인한다(대상별 시간 제한 5초, 한 바퀴 10초). 느린 대상이 다른 대상을 붙잡지 않는다.
- **3번 연속 실패하면** 알린다(약 1분 30초). 한두 번의 실패로는 알리지 않는다.
- 복구되면 끊긴 시간과 함께 알리고, 계속 죽어 있으면 1시간마다 다시 알린다.
- 같은 바퀴에 여러 대상이 죽으면 알림 하나로 묶는다.
- 재시작해도 이미 알린 장애를 다시 알리지 않는다. 재시작 직후에는 3번 확인하기 전까지 알리지 않는다.

## 감시 대상

`src/main/resources/monitors.yml` 에 적는다. 주소는 적지 않고 서버 `.env` 의 환경변수로 가리킨다. 형식이 틀리거나 가리킨 환경변수가 없으면 뜨지 않는다.

## API

| 경로 | 인증 | 설명 |
|---|---|---|
| `GET /health` | 없음 | 감시 루프가 살아 있는가(마지막 한 바퀴가 2분 안) |
| `POST /events` | `X-Alert-Secret` | 다른 서버가 알릴 일을 보낸다: `{source, kind, severity, key, title, detail}`. 같은 `key` 는 10분에 한 번만 발송 |
| `GET /monitors` | `X-Alert-Secret` | 대상별 현재 상태 |
| `GET /incidents?limit=` | `X-Alert-Secret` | 알린 일의 기록(30일 보관) |
| `POST /admin/drain` | 자리의 로컬 포트로만 | 무중단 배포의 정리 알림 — 구 자리는 확인과 발송을 멈춘다 |

## 독립성

- 다른 DB 서버나 Redis 에 기대지 않는다. 기록은 파일(SQLite, `data/alert.db`)이고, 기록에 실패해도 감시와 발송은 계속한다.
- 모든 채널이 실패한 알림은 버리지 않고 보관했다가 다음 바퀴에 다시 보낸다(상한 200건).
- 채널 주소 같은 비밀값은 서버 `.env` 에만 둔다. `.env.example` 에 항목 이름이 있다.

## 구동

배포는 ci-cd 파이프라인이 한다(테스트 → 이미지 빌드 → 무중단 전환). 처음 올릴 때와 수동 조작은 ci-cd 저장소 README 의 "무중단 배포" 절을 따른다.

```
cp .env.example .env    # 값 채우기
mkdir -p deploy/active data
cp deploy/nginx/nginx.conf deploy/active/nginx.conf
cp deploy/nginx/upstream-blue.conf deploy/active/upstream.conf
docker compose up -d --build alert-blue nginx
```

테스트: `./gradlew test`

## 집 밖의 감시자 (`edge/watchdog.sh`)

알림 서버는 집 안에 있어서 집 전체나 자기 자신이 죽으면 스스로 알릴 수 없다. 엣지 서버에서 1분마다 이 스크립트를 돌려 사이트와 알림 서버를 직접 확인한다.

- 3번 연속(3분) 안 보이면 엣지가 디스코드로 직접 알리고, 다시 보이면 끊긴 시간을 알린다.
- 그보다 짧은 끊김은 알리지 않고 `outages.log` 에 시작·끝·길이만 남긴다(끊김 빈도를 재는 용도).
- 사이트가 안 보일 때는 알림 서버 알림을 따로 보내지 않는다(같은 원인으로 둘 다 실패하므로).

설치(엣지 서버에서):
```
sudo install -m 0755 watchdog.sh /usr/local/bin/alert-watchdog
sudo install -m 0600 /dev/null /etc/alert-watchdog.env     # HOME_TARGET · DISCORD_WEBHOOK_URL · ALERT_SECRET 를 적는다
sudo mkdir -p /var/lib/alert-watchdog
echo '* * * * * root /usr/local/bin/alert-watchdog' | sudo tee /etc/cron.d/alert-watchdog
```
