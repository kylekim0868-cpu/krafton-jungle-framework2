package com.minidbms.server;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 직접 구현한 고정 크기 스레드 풀.
 *
 * <pre>
 *   submit(작업) ──▶ [ 대기 큐 (크기 제한) ] ──▶ worker-1  ┐
 *                                            ──▶ worker-2  ├ 큐에서 작업을 하나씩 꺼내 실행
 *                                            ──▶ worker-N  ┘
 * </pre>
 *
 * <ul>
 *   <li>워커 스레드 N 개를 미리 만들어 두고 재사용한다 (요청마다 스레드를 새로 만들지 않는다).</li>
 *   <li>워커가 모두 바쁘면 작업은 큐에서 기다린다.</li>
 *   <li>큐까지 가득 차면 {@link #trySubmit} 이 false 를 돌려준다.
 *       무한정 쌓아 두다 메모리가 터지는 대신, 호출자가 "지금은 바쁨(503)"으로 응답하게 한다.</li>
 * </ul>
 */
public final class ThreadPool implements AutoCloseable {

    private final BlockingQueue<Runnable> queue;
    private final Thread[] workers;
    private volatile boolean shutdown;

    private final AtomicInteger activeCount = new AtomicInteger();
    private final AtomicLong completedCount = new AtomicLong();
    private final AtomicLong rejectedCount = new AtomicLong();

    public ThreadPool(int threadCount, int queueCapacity) {
        if (threadCount < 1 || queueCapacity < 1) {
            throw new IllegalArgumentException("threadCount and queueCapacity must be >= 1");
        }
        this.queue = new ArrayBlockingQueue<>(queueCapacity);
        this.workers = new Thread[threadCount];
        for (int i = 0; i < threadCount; i++) {
            workers[i] = new Thread(this::workerLoop, "db-worker-" + (i + 1));
            workers[i].start();
        }
    }

    /**
     * 작업을 큐에 넣는다. 기다리지 않는다.
     *
     * @return 넣었으면 true, 큐가 가득 찼거나 종료 중이면 false
     */
    public boolean trySubmit(Runnable task) {
        if (shutdown || !queue.offer(task)) {
            rejectedCount.incrementAndGet();
            return false;
        }
        return true;
    }

    private void workerLoop() {
        // 종료 요청이 와도 큐에 남은 작업은 끝까지 처리한다
        while (!shutdown || !queue.isEmpty()) {
            Runnable task;
            try {
                task = queue.poll(100, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                break;
            }
            if (task == null) {
                continue;
            }
            activeCount.incrementAndGet();
            try {
                task.run();
            } catch (Throwable t) {
                // 작업 하나가 실패해도 워커 스레드는 죽지 않고 다음 작업을 처리한다
                System.err.println("[" + Thread.currentThread().getName() + "] task failed: " + t);
            } finally {
                activeCount.decrementAndGet();
                completedCount.incrementAndGet();
            }
        }
    }

    public int threadCount() {
        return workers.length;
    }

    /** 지금 작업을 실행 중인 워커 수 */
    public int activeCount() {
        return activeCount.get();
    }

    /** 큐에서 기다리는 작업 수 */
    public int queuedCount() {
        return queue.size();
    }

    public int queueCapacity() {
        return queue.size() + queue.remainingCapacity();
    }

    public long completedCount() {
        return completedCount.get();
    }

    public long rejectedCount() {
        return rejectedCount.get();
    }

    /** 새 작업을 받지 않고, 진행 중이거나 큐에 있는 작업이 끝나기를 최대 5초 기다린다. */
    @Override
    public void close() {
        shutdown = true;
        long deadline = System.currentTimeMillis() + 5_000;
        for (Thread worker : workers) {
            try {
                worker.join(Math.max(1, deadline - System.currentTimeMillis()));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        for (Thread worker : workers) {
            if (worker.isAlive()) {
                worker.interrupt();
            }
        }
    }
}
