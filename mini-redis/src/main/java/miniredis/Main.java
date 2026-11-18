package miniredis;

import miniredis.http.MiniRedisHttpServer;
import miniredis.store.KeyValueStore;

import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.time.Clock;

public final class Main {
    private static final System.Logger LOG = System.getLogger(Main.class.getName());
    private Main() { }

    public static void main(String[] args) throws Exception {
        if (args.length < 2 || args.length > 3) {
            throw new IllegalArgumentException("Usage: miniredis.Main <port> <expectedEntries> [aofPath]");
        }
        int port = Integer.parseInt(args[0]); // 서버 실행 시 필요한 port번호
        int expectedEntries = Integer.parseInt(args[1]); // 앞으로 저장할 것으로 예상하는 Key-Value 항목 수
        var address = new InetSocketAddress("127.0.0.1", port);
        // ✔︎ 핵심: 예상 데이터 개수를 기준으로 해시 테이블의 초기 버킷 수를 결정. 버킷 수를 넉넉하게 확보해 초기 데이터 삽입 과정에서 Resize가 자주 발생하는 것을 줄이려는 목적.
        // port expectedEntries - 메모리 기반 저장소, prot expectedEntries aofPath - AOF 영속성을 사용하는 저장소
        var store = args.length == 3 ? KeyValueStore.openPersistent(expectedEntries, Clock.systemUTC(), Path.of(args[2]))
                : new KeyValueStore(expectedEntries); 
        // 서버 객체를 생성하면서 이미 만들어진 KeyValueStore 객체 전달
        // KeyValueStore: 실제 데이터를 관리하는 객체 (메모리 저장소 또는 AOF 저장소 생성)
        // MiniRedisHttpServer: HTTP 요청을 수신하는 서버
        // 서버는 생성자에서 전닯잗은 저장소를 이용해 요청을 처리한다
        // 여러 요청이 같은 저장소 객체를 공유하므로 이전 요청에서 저장한 값을 다음 요청에서 조회 가능
        var server = new MiniRedisHttpServer(address, store);
        try {
            server.start();
        } catch (Exception | Error failure) {
            try { server.close(); } catch (RuntimeException closeFailure) { failure.addSuppressed(closeFailure); }
            try { store.close(); } catch (java.io.IOException closeFailure) { failure.addSuppressed(closeFailure); }
            throw failure;
        }
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try { server.close(); } finally {
                try { store.close(); } catch (java.io.IOException failure) {
                    LOG.log(System.Logger.Level.ERROR, "Failed to close persistent store", failure);
                }
            }
        }, "mini-redis-shutdown"));
        System.out.println("Mini Redis listening on http://127.0.0.1:" + server.port());
    }
}
