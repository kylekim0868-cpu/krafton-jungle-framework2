package miniredis.http;

import com.sun.net.httpserver.HttpServer;
import miniredis.store.KeyValueStore;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ScheduledExecutorService;

/** 저장소 하나를 공유하는 HTTP 서버. 고정 4개 작업 스레드가 저장소의 공통 잠금을 사용한다. */
public final class MiniRedisHttpServer implements AutoCloseable {
    private final InetSocketAddress address;
    private final KeyValueStore store;
    private final KeysHandler handler;
    private final ScheduledExecutorService expiration = Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().name("mini-redis-expiration").factory());
    private final ExecutorService executor;
    private HttpServer server;
    private boolean closed;

    public MiniRedisHttpServer(InetSocketAddress address, KeyValueStore store) {
        this.address = Objects.requireNonNull(address, "address");
        this.store = Objects.requireNonNull(store, "store");
        handler = new KeysHandler(store);
        // 작업 스레드가 최대 4개인 고정 크기 스레드 풀 생성
        executor = Executors.newFixedThreadPool(4, Thread.ofPlatform().name("mini-redis-http-", 0).factory());
    }

    public synchronized void start() throws IOException {
        if (closed) {
            throw new IllegalStateException("server is closed");
        }
        if (server != null) {
            throw new IllegalStateException("server is already started");
        }
        // 시작 전에 닫힌 JDK HttpServer는 바인딩 자원을 반환하지 않을 수 있어 생성도 시작 시 수행한다.
        var dashboard = new DashboardHandler(store);
        server = HttpServer.create(address, 0);
        server.createContext("/", handler);
        server.createContext("/dashboard", dashboard);
        server.createContext("/stats", dashboard);
        server.setExecutor(executor);
        server.start();
        expiration.scheduleWithFixedDelay(store::expireEntries, 1, 1, TimeUnit.SECONDS);
    }

    public synchronized int port() {
        if (server == null) {
            throw new IllegalStateException("server is not started");
        }
        return server.getAddress().getPort();
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        if (server != null) {
            server.stop(0);
        }
        expiration.shutdownNow();
        executor.shutdown();
        try {
            if (!expiration.awaitTermination(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Expiration worker did not terminate");
            }
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                executor.shutdownNow();
                if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("HTTP worker did not terminate");
                }
            }
        } catch (InterruptedException interrupted) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    boolean isTerminated() {
        return executor.isTerminated();
    }
}
