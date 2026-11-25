# study-note-deploy-system-alert

알림 서버 — 서비스가 죽거나, 의존 서비스가 안 되거나, 배포가 실패하면 사람에게 알린다. Kotlin · Spring Boot.

```
감시 대상(front · backend · llm · 배포 시스템 · 관측 서버 · 어드민)  ◀── 30초마다 확인 ── 알림 서버 ──▶ 디스코드 → (실패 시) 슬랙 → (실패 시) 메일
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
| `GET /health` | 없음 | 감시 루프가 살아 있는가. `ok`(마지막 한 바퀴가 2분 안) · `starting`(뜬 지 2분 안) · `standby`(대기 자리, 200) · `stalled`(활성인데 멈춤, 503) |
| `POST /events` | `X-Alert-Secret` | 다른 서버가 알릴 일을 보낸다: `{source, kind, severity, key, title, detail}`. 같은 `key` 는 10분에 한 번만 발송 |
| `GET /monitors` | `X-Alert-Secret` | 대상별 현재 상태 |
| `GET /incidents?limit=` | `X-Alert-Secret` | 알린 일의 기록(30일 보관) |
| `GET /settings` | `X-Alert-Settings-Secret` | 지금 설정 — 감시 대상(이름 · 기준 · 가린 주소: 환경 변수 이름과 경로만) · 채널(종류 · 설정됨 · 순서) · 규칙 · 맡은 자리 여부. 비밀값 · 호스트 · IP · 포트는 내지 않는다 |
| `POST /settings/test-send` | `X-Alert-Settings-Secret` | `{requested_by}`(200자까지). 설정된 채널마다 따로 한 번 시험 알림을 보내고 채널별 결과를 답한다. 사건 · 미발송 보관함에 남지 않는다. 두 자리를 합쳐 30초에 한 번(넘으면 429) |
| `POST /admin/drain` | 자리의 로컬 포트로만 | 무중단 배포의 정리 알림 — 구 자리가 곧바로 감시를 내려놓는다 |

## 무중단 배포와 활성 자리

배포 중에는 두 자리(blue·green)가 잠시 함께 떠 있다. 감시·발송·요약은 **프록시(nginx)가 실제로 요청을 보내는 자리 하나만** 한다 — 같은 알림이 두 번 가지 않고, 아무도 감시하지 않는 공백도 생기지 않게.

- 각 자리는 매 바퀴 프록시의 `X-Deploy-Slot` 을 보고 자기 자리면 감시하고, 아니면 대기한다(`standby`).
- 대기에서 활성이 될 때 기록에서 직전의 DOWN 상태와 못 보낸 알림을 이어받는다. 앞 자리가 이미 알린 장애·복구를 다시 알리지 않는다.
- `/admin/drain` 은 구 자리를 곧바로 대기로 내린다. 전환이 되돌려져 프록시가 여전히 그 자리로 보내면 다음 바퀴에 다시 맡는다.
- 프록시에 물어볼 수 없으면 직전 판단을 유지한다. 뜬 뒤 2분 동안 한 번도 묻지 못하면 스스로 맡는다(알리지 않는 쪽보다 알리는 쪽으로 실패).
- 사건(`/events`)은 받은 자리가 처리한다. 같은 key 의 10분 억제는 기록으로 판단하므로 자리가 바뀌어도 이어진다.

## 독립성

- 다른 DB 서버나 Redis 에 기대지 않는다. 기록은 파일(SQLite, `data/alert.db`)이고, 기록에 실패해도 감시와 발송은 계속한다.
- 발송은 전용 스레드가 한다 — 채널이 느리거나 죽어도 다음 바퀴와 사건 접수가 밀리지 않는다.
- 모든 채널이 실패한 알림은 버리지 않고 보관했다가 다음 바퀴에 다시 보낸다(상한 200건, 넘으면 오래된 것부터 버리고 error 로그). 재시작해도 최근 24시간의 미발송 건은 기록에서 되살린다.
- 채널 주소 같은 비밀값은 서버 `.env` 에만 둔다. `.env.example` 에 항목 이름이 있다.
- `/settings` 두 경로는 여럿이 쓰는 `ALERT_SECRET` 이 아니라 `ALERT_SETTINGS_SECRET`(어드민과 알림 서버만)으로 연다. 비어 있으면 늘 거절하고, 앱과 다른 경로는 그대로 돈다.

## 구동

배포는 ci-cd 파이프라인이 한다(테스트 → 이미지 빌드 → 무중단 전환). 처음 올릴 때와 수동 조작은 ci-cd 저장소 README 의 "무중단 배포" 절을 따른다.

```
cp .env.example .env    # 값 채우기
mkdir -p deploy/active data
cp deploy/nginx/nginx.conf deploy/active/nginx.conf
cp deploy/nginx/upstream-blue.conf deploy/active/upstream.conf
docker compose up -d --build alert-blue nginx
```

- 접속 기록(nginx — 루트 프로젝트 `docs/cross-cutting/plans/2026-11-25/traffic-map/communication-protocol.md` 3절): 요청마다 표준 출력에 JSON 한 줄(14칸, `service` 는 `alert`). 접속 주소 · 쿼리 문자열 · 리퍼러 · User-Agent · 쿠키 · 인증 머리글은 싣지 않고, 경로는 첫 `?` 앞 1024자까지다. 오류 기록은 표준 오류(주소가 든다 — 수집하지 않는다). 도커 로그는 10MB × 3 으로 묶는다.
  - 요청 ID(`X-Request-Id`): 사설 대역 · 루프백에서 온 꼴(`^[A-Za-z0-9_-]{1,64}$`) 맞는 값만 믿고, 아니면 nginx 가 새로 만들어 앱에 넘기고 응답 머리글로 돌려준다. 호출자(`X-Caller-Service`)도 사설 대역의 아는 이름만 그대로, 나머지는 `outside` 로 덮어써 넘긴다 — 지도 표시용이지 인증이 아니다.

테스트: `./gradlew test`

## 집 밖의 감시자 (`edge/watchdog.sh`)

알림 서버는 집 안에 있어서 집 전체나 자기 자신이 죽으면 스스로 알릴 수 없다. 엣지 서버에서 1분마다 이 스크립트를 돌려 사이트와 알림 서버를 직접 확인한다.

- 3번 연속(3분) 안 보이면 엣지가 디스코드로 직접 알리고, 다시 보이면 끊긴 시간을 알린다.
- 그보다 짧은 끊김은 알리지 않고 `outages.log` 에 시작·끝·길이만 남긴다(끊김 빈도를 재는 용도).
- 두 경로를 매번 따로 확인한다. 사이트가 안 보이는 동안에는 알림 서버 알림만 미룬다(집 전체가 끊기면 둘 다 실패하므로). 사이트가 돌아왔는데도 알림 서버가 안 보이면 그때 알린다.
- 알림 서버가 `standby` 로만 보이는 것도 실패로 센다(아무도 감시하지 않는 상태).
- 디스코드가 거절하면(4xx·5xx) 알렸다고 적지 않고 다음 실행에 다시 보낸다.

설치(엣지 서버에서):
```
sudo install -m 0755 watchdog.sh /usr/local/bin/alert-watchdog
sudo install -m 0600 /dev/null /etc/alert-watchdog.env     # HOME_TARGET · DISCORD_WEBHOOK_URL · ALERT_SECRET 를 적는다
sudo mkdir -p /var/lib/alert-watchdog
echo '* * * * * root /usr/local/bin/alert-watchdog' | sudo tee /etc/cron.d/alert-watchdog
```
