package com.minidbms.server;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Timeout(20)
class ThreadPoolTest {

    @Test
    @DisplayName("제출한 작업이 모두 실행되고, 워커 스레드 N 개가 나눠서 처리한다")
    void runsAllTasksOnWorkerThreads() throws InterruptedException {
        int threads = 4;
        int tasks = 200;
        try (ThreadPool pool = new ThreadPool(threads, tasks)) {
            Set<String> threadNames = ConcurrentHashMap.newKeySet();
            AtomicInteger sum = new AtomicInteger();
            CountDownLatch done = new CountDownLatch(tasks);

            for (int i = 1; i <= tasks; i++) {
                int n = i;
                assertTrue(pool.trySubmit(() -> {
                    threadNames.add(Thread.currentThread().getName());
                    sum.addAndGet(n);
                    done.countDown();
                }));
            }

            assertTrue(done.await(10, TimeUnit.SECONDS));
            assertEquals(tasks * (tasks + 1) / 2, sum.get());
            assertTrue(threadNames.size() <= threads);
            assertTrue(threadNames.stream().allMatch(name -> name.startsWith("db-worker-")));
        }
    }

    @Test
    @DisplayName("워커 수만큼만 동시에 실행된다")
    void concurrencyIsBoundedByThreadCount() throws InterruptedException {
        int threads = 3;
        try (ThreadPool pool = new ThreadPool(threads, 100)) {
            AtomicInteger running = new AtomicInteger();
            AtomicInteger maxRunning = new AtomicInteger();
            CountDownLatch done = new CountDownLatch(30);

            for (int i = 0; i < 30; i++) {
                pool.trySubmit(() -> {
                    int now = running.incrementAndGet();
                    maxRunning.accumulateAndGet(now, Math::max);
                    sleep(20);
                    running.decrementAndGet();
                    done.countDown();
                });
            }

            assertTrue(done.await(10, TimeUnit.SECONDS));
            assertEquals(threads, maxRunning.get());
        }
    }

    @Test
    @DisplayName("워커와 큐가 모두 차면 새 작업을 거절한다")
    void rejectsWhenQueueIsFull() throws InterruptedException {
        try (ThreadPool pool = new ThreadPool(1, 2)) {
            CountDownLatch workerBusy = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);

            assertTrue(pool.trySubmit(() -> {
                workerBusy.countDown();
                await(release);
            }));
            assertTrue(workerBusy.await(5, TimeUnit.SECONDS)); // 워커 1개가 붙잡혀 있다

            assertTrue(pool.trySubmit(() -> { }));   // 큐 1/2
            assertTrue(pool.trySubmit(() -> { }));   // 큐 2/2
            assertFalse(pool.trySubmit(() -> { }));  // 가득 참 → 거절
            assertEquals(1, pool.rejectedCount());
            assertEquals(2, pool.queuedCount());
            assertEquals(1, pool.activeCount());

            release.countDown();
        }
    }

    @Test
    @DisplayName("작업이 예외를 던져도 워커는 죽지 않고 다음 작업을 처리한다")
    void workerSurvivesTaskFailure() throws InterruptedException {
        try (ThreadPool pool = new ThreadPool(1, 10)) {
            CountDownLatch done = new CountDownLatch(1);
            pool.trySubmit(() -> {
                throw new IllegalStateException("boom (expected in test)");
            });
            pool.trySubmit(done::countDown);
            assertTrue(done.await(5, TimeUnit.SECONDS));
        }
    }

    @Test
    @DisplayName("close: 큐에 남은 작업을 마저 처리하고, 이후 제출은 거절한다")
    void closeDrainsQueue() {
        ThreadPool pool = new ThreadPool(2, 100);
        AtomicInteger executed = new AtomicInteger();
        for (int i = 0; i < 50; i++) {
            pool.trySubmit(() -> {
                sleep(2);
                executed.incrementAndGet();
            });
        }
        pool.close();
        assertEquals(50, executed.get());
        assertEquals(50, pool.completedCount());
        assertFalse(pool.trySubmit(() -> { }));
    }

    @Test
    @DisplayName("잘못된 크기는 거부")
    void invalidArguments() {
        assertThrows(IllegalArgumentException.class, () -> new ThreadPool(0, 10));
        assertThrows(IllegalArgumentException.class, () -> new ThreadPool(1, 0));
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
