package com.miniredis.core;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * TTL 만료 관리자.
 *
 * <p><b>1. Lazy Expiration (수동적 만료)</b><br>
 * {@link HashTable#get(String)} 내부에서 조회 시점에 만료를 검사하고 즉시 삭제한다.
 * 클라이언트는 만료된 키를 절대 볼 수 없다. 하지만 아무도 조회하지 않는 만료 키는 메모리에 계속 남는다.</p>
 *
 * <p><b>2. Active Expiration (능동적 만료)</b> — 이 클래스의 역할<br>
 * Lazy 방식의 메모리 누수를 막기 위해, 데몬 스레드가 1초 주기로 모든 세그먼트/버킷을 순회하며
 * 만료 엔트리를 제거한다. 세그먼트 단위로 Write Lock 을 잡기 때문에 정리 중에도 다른 세그먼트는 서비스된다.</p>
 */
public class TTLManager {

    private static final Logger log = LoggerFactory.getLogger(TTLManager.class);

    private final ConcurrentHashMap store;
    private final long intervalMillis;

    private ScheduledExecutorService scheduler;

    /** 누적 Active Expiration 삭제 건수 (통계용) */
    private final AtomicLong totalExpired = new AtomicLong();
    /** 마지막 스캔 시각 */
    private volatile long lastRunAt = 0L;

    public TTLManager(ConcurrentHashMap store) {
        this(store, 1000L);
    }

    public TTLManager(ConcurrentHashMap store, long intervalMillis) {
        this.store = store;
        this.intervalMillis = intervalMillis;
    }

    /** Spring 빈 초기화 시 스케줄러 시작 */
    @PostConstruct
    public synchronized void start() {
        if (scheduler != null && !scheduler.isShutdown()) {
            return; // 중복 시작 방지
        }
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "ttl-active-expirer");
            t.setDaemon(true); // JVM 종료를 막지 않는 데몬 스레드
            return t;
        });
        // fixedDelay: 이전 스캔이 끝난 뒤 intervalMillis 후 다음 스캔 (스캔이 길어져도 겹치지 않음)
        scheduler.scheduleWithFixedDelay(this::expireCycle, intervalMillis, intervalMillis, TimeUnit.MILLISECONDS);
        log.info("[TTL] Active expiration scheduler started (interval={}ms)", intervalMillis);
    }

    /** 한 번의 Active Expiration 사이클. 예외가 스케줄러 밖으로 나가면 이후 실행이 중단되므로 모두 잡는다. */
    void expireCycle() {
        try {
            int removed = store.removeExpired();
            lastRunAt = System.currentTimeMillis();
            if (removed > 0) {
                totalExpired.addAndGet(removed);
                log.debug("[TTL] Active expiration removed {} keys", removed);
            }
        } catch (Throwable t) {
            log.error("[TTL] Active expiration failed", t);
        }
    }

    /** 수동으로 즉시 한 사이클 실행 (테스트/디버깅용) */
    public int runNow() {
        int removed = store.removeExpired();
        totalExpired.addAndGet(removed);
        lastRunAt = System.currentTimeMillis();
        return removed;
    }

    /** Spring 빈 종료 시 스케줄러 정지 */
    @PreDestroy
    public synchronized void stop() {
        if (scheduler != null) {
            scheduler.shutdownNow();
            scheduler = null;
            log.info("[TTL] Active expiration scheduler stopped");
        }
    }

    public boolean isRunning() {
        return scheduler != null && !scheduler.isShutdown();
    }

    public long getTotalExpired() {
        return totalExpired.get();
    }

    public long getLastRunAt() {
        return lastRunAt;
    }
}
