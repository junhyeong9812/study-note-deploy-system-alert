package xyz.junproject.alert.monitor

import java.util.UUID

/** 추적의 ID 를 싣는 머리글 — 받는 쪽 nginx 의 접속 기록이 이 값을 남긴다 */
const val REQUEST_ID_HEADER = "X-Request-Id"

/** 우리 서비스를 부를 때 자기 이름을 싣는 머리글 — 받는 쪽 nginx 가 지도의 부른 쪽으로 기록한다(인증에 쓰지 않는다) */
const val CALLER_SERVICE_HEADER = "X-Caller-Service"

/** 알림 서버의 규약 이름 — 설정 · 환경 변수 · 들어온 요청에서 읽지 않는다 */
const val CALLER_SERVICE = "alert"

/**
 * 들어온 요청 없이 alert 가 스스로 시작하는 호출(감시 · 자리 확인)마다 새로 만드는 추적의 ID.
 * 꼴은 16진수 소문자 32자 — nginx 의 `$request_id` 와 같다. 바깥 서비스(웹훅 · 메일)로 가는 호출에는 싣지 않는다.
 */
fun newRequestId(): String = UUID.randomUUID().toString().replace("-", "")
