package com.miniredis;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Mini Redis 애플리케이션 진입점.
 * 실행 후 http://localhost:8080 에서 시각화 대시보드를 확인할 수 있다.
 */
@SpringBootApplication
public class MiniRedisApplication {

    public static void main(String[] args) {
        SpringApplication.run(MiniRedisApplication.class, args);
    }
}
