package xyz.junproject.alert.monitor

import java.util.UUID

/** 추적의 ID 를 싣는 머리글 — 받는 쪽 nginx 의 접속 기록이 이 값을 남긴다 */
const val REQUEST_ID_HEADER = "X-Request-Id"

/**
 * 들어온 요청 없이 alert 가 스스로 시작하는 호출(감시 · 자리 확인)마다 새로 만드는 추적의 ID.
 * 꼴은 16진수 소문자 32자 — nginx 의 `$request_id` 와 같다. 바깥 서비스(웹훅 · 메일)로 가는 호출에는 싣지 않는다.
 */
fun newRequestId(): String = UUID.randomUUID().toString().replace("-", "")
