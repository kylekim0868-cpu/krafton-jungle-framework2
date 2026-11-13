package com.miniredis.core;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TTLManagerTest {

    private TTLManager ttlManager;

    @AfterEach
    void tearDown() {
        if (ttlManager != null) {
            ttlManager.stop();
        }
    }

    @Test
    @DisplayName("1. TTL 2초 저장 → 1초 뒤 조회 시 값 존재")
    void valueExistsBeforeExpiry() throws InterruptedException {
        ConcurrentHashMap map = new ConcurrentHashMap();
        map.put("k", "v", 2);
        Thread.sleep(1_000);
        assertThat(map.get("k")).isEqualTo("v");
    }

    @Test
    @DisplayName("2. TTL 2초 저장 → 3초 뒤 조회 시 null (Lazy Expiration, 스케줄러 없이)")
    void lazyExpiration() throws InterruptedException {
        ConcurrentHashMap map = new ConcurrentHashMap();
        map.put("k", "v", 2);
        Thread.sleep(3_000);

        // 스케줄러가 없으므로 물리적으로는 아직 남아 있음
        assertThat(map.rawSize()).isEqualTo(1);
        // 조회하는 순간 Lazy Expiration 으로 삭제되고 null 반환
        assertThat(map.get("k")).isNull();
        assertThat(map.rawSize()).isZero();
    }

    @Test
    @DisplayName("2-1. HashTable 단독으로도 Lazy Expiration 동작")
    void lazyExpirationInHashTable() throws InterruptedException {
        HashTable table = new HashTable();
        table.put("k", "v", 1);
        Thread.sleep(1_200);
        assertThat(table.rawSize()).isEqualTo(1);
        assertThat(table.get("k")).isNull();
        assertThat(table.rawSize()).isZero();
    }

    @Test
    @DisplayName("3. Active Expiration: 조회하지 않아도 스케줄러가 만료 키를 자동 제거")
    void activeExpiration() throws InterruptedException {
        ConcurrentHashMap map = new ConcurrentHashMap();
        ttlManager = new TTLManager(map, 1_000);
        ttlManager.start();

        for (int i = 0; i < 50; i++) {
            map.put("short-" + i, i, 1);   // 1초 후 만료
        }
        map.put("forever", "x", 0);        // 만료 없음
        assertThat(map.rawSize()).isEqualTo(51);

        // 만료(1초) + 스케줄러 주기(1초) + 여유
        Thread.sleep(3_000);

        // get() 을 한 번도 호출하지 않았는데도 물리적으로 제거되어 있어야 함
        assertThat(map.rawSize()).isEqualTo(1);
        assertThat(ttlManager.getTotalExpired()).isEqualTo(50);
        assertThat(map.get("forever")).isEqualTo("x");
    }

    @Test
    @DisplayName("3-1. stop() 후 스케줄러 종료")
    void stopScheduler() {
        ttlManager = new TTLManager(new ConcurrentHashMap(), 1_000);
        ttlManager.start();
        assertThat(ttlManager.isRunning()).isTrue();
        ttlManager.stop();
        assertThat(ttlManager.isRunning()).isFalse();
    }
}
