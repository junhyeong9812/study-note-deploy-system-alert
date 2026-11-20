package xyz.junproject.alert

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.scheduling.annotation.EnableScheduling

/** 알림 서버 — 서비스 생존을 감시하고, 다른 서버가 보낸 사건을 받아 사람에게 알린다. */
@SpringBootApplication
@EnableScheduling
class AlertApplication

fun main(arguments: Array<String>) {
    runApplication<AlertApplication>(*arguments)
}
