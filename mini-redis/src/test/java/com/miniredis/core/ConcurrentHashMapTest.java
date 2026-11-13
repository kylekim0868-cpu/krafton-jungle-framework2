package com.miniredis.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class ConcurrentHashMapTest {

    @Test
    @DisplayName("1. 100개 스레드 × 1,000건 동시 put → size == 100,000")
    void concurrentPutsAreConsistent() throws Exception {
        ConcurrentHashMap map = new ConcurrentHashMap(16, 4);
        int threads = 100;
        int perThread = 1_000;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch startGate = new CountDownLatch(1); // 모든 스레드를 동시에 출발시키기 위한 게이트
        List<Future<?>> futures = new ArrayList<>();

        for (int t = 0; t < threads; t++) {
            final int tid = t;
            futures.add(pool.submit(() -> {
                startGate.await();
                for (int i = 0; i < perThread; i++) {
                    map.put("t" + tid + "-k" + i, i, 0);
                }
                return null;
            }));
        }
        startGate.countDown();
        for (Future<?> f : futures) {
            f.get(30, TimeUnit.SECONDS); // 작업 중 예외가 있으면 여기서 다시 던져짐
        }
        pool.shutdown();

        assertThat(map.size()).isEqualTo(threads * perThread);
        assertThat(map.getAllEntries()).hasSize(threads * perThread);
        // 샘플 검증
        assertThat(map.get("t0-k0")).isEqualTo(0);
        assertThat(map.get("t99-k999")).isEqualTo(999);
    }

    @Test
    @DisplayName("2. 쓰기/읽기/삭제/전체조회/만료정리를 혼합 실행해도 예외 없음")
    void mixedReadWriteNoExceptions() throws Exception {
        ConcurrentHashMap map = new ConcurrentHashMap(16, 4);
        int threads = 32;
        int ops = 5_000;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch startGate = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();

        for (int t = 0; t < threads; t++) {
            final int role = t % 4;
            futures.add(pool.submit(() -> {
                startGate.await();
                ThreadLocalRandom rnd = ThreadLocalRandom.current();
                for (int i = 0; i < ops; i++) {
                    String key = "key-" + rnd.nextInt(2_000);
                    switch (role) {
                        case 0 -> map.put(key, i, rnd.nextBoolean() ? 0 : 1); // 일부는 TTL 1초
                        case 1 -> map.get(key);
                        case 2 -> map.remove(key);
                        default -> {
                            // 전체 순회 계열 연산도 동시에 실행
                            if (i % 50 == 0) {
                                List<Map<String, Object>> all = map.getAllEntries();
                                assertThat(all).isNotNull();
                                map.getBucketDistribution();
                                map.removeExpired();
                            } else {
                                map.getEntry(key);
                            }
                        }
                    }
                }
                return null;
            }));
        }
        startGate.countDown();
        for (Future<?> f : futures) {
            f.get(60, TimeUnit.SECONDS); // ConcurrentModificationException, NPE 등이 있으면 실패
        }
        pool.shutdown();

        // 내부 구조가 깨지지 않았는지: 전체 목록 크기와 size 가 일치해야 함
        assertThat(map.getAllEntries().size()).isEqualTo(map.size());
    }

    @Test
    @DisplayName("3. clear()(전 세그먼트 락)와 put/get 이 경합해도 데드락 없음")
    void segmentsAreIndependent() throws Exception {
        ConcurrentHashMap map = new ConcurrentHashMap(16, 4);
        // clear() 는 모든 세그먼트 Write Lock 을 0번부터 순서대로 잡는다.
        // 개별 put/get 과 경합해도 락 순서가 고정되어 있으므로 교착상태가 없어야 한다.
        ExecutorService pool = Executors.newFixedThreadPool(8);
        List<Future<?>> futures = new ArrayList<>();
        for (int t = 0; t < 8; t++) {
            final int tid = t;
            futures.add(pool.submit(() -> {
                for (int i = 0; i < 2_000; i++) {
                    if (tid == 0 && i % 200 == 0) {
                        map.clear(); // 모든 세그먼트 락을 고정 순서로 획득
                    } else {
                        map.put("k" + tid + "-" + i, i, 0);
                        map.get("k" + tid + "-" + (i / 2));
                    }
                }
                return null;
            }));
        }
        for (Future<?> f : futures) {
            f.get(30, TimeUnit.SECONDS); // 데드락이면 타임아웃으로 실패
        }
        pool.shutdown();
        assertThat(map.size()).isGreaterThanOrEqualTo(0);
    }
}
