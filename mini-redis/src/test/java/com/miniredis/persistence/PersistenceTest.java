package com.miniredis.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.miniredis.core.ConcurrentHashMap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class PersistenceTest {

    @TempDir
    Path dataDir;

    private final ObjectMapper mapper = new ObjectMapper();
    private AOFManager aof;

    @AfterEach
    void tearDown() {
        if (aof != null) {
            aof.close();
        }
    }

    /** 컨트롤러와 동일하게: 메모리 반영 후 AOF 기록 */
    private void set(ConcurrentHashMap store, String key, Object value, int ttl) {
        store.put(key, value, ttl);
        aof.appendSet(key, value, ttl);
    }

    @Test
    @DisplayName("1. 데이터 3건 저장 → AOF 파일에 3줄 기록")
    void aofWritesOneLinePerCommand() throws Exception {
        ConcurrentHashMap store = new ConcurrentHashMap();
        aof = new AOFManager(store, dataDir, mapper);
        aof.init();

        set(store, "k1", "v1", 0);
        set(store, "k2", "hello world", 60);   // 공백 포함 값
        set(store, "k3", Map.of("n", 3), 0);   // 객체 값

        List<String> lines = Files.readAllLines(dataDir.resolve(AOFManager.AOF_FILE_NAME), StandardCharsets.UTF_8);
        assertThat(lines).hasSize(3);
        assertThat(lines).allMatch(l -> l.startsWith("SET "));
        assertThat(lines.get(1).split(" ")).hasSize(5); // SET key value ttl timestamp
    }

    @Test
    @DisplayName("2. clear() → AOF Replay → 3건 모두 정상 조회")
    void aofReplayRestoresData() throws Exception {
        ConcurrentHashMap store = new ConcurrentHashMap();
        aof = new AOFManager(store, dataDir, mapper);
        aof.init();

        set(store, "k1", "v1", 0);
        set(store, "k2", "hello world", 60);
        set(store, "k3", Map.of("n", 3), 0);

        store.clear();
        assertThat(store.size()).isZero();

        int applied = aof.replay();
        assertThat(applied).isEqualTo(3);
        assertThat(store.size()).isEqualTo(3);
        assertThat(store.get("k1")).isEqualTo("v1");
        assertThat(store.get("k2")).isEqualTo("hello world");
        assertThat(store.get("k3")).isEqualTo(Map.of("n", 3));
        // TTL 도 복원되어야 함 (원래 만료 시각 기준)
        assertThat(store.getEntry("k2").remainingTtlSeconds(System.currentTimeMillis())).isBetween(1L, 60L);
    }

    @Test
    @DisplayName("2-1. 서버 재시작 시뮬레이션: DEL/FLUSH/만료 SET 이 반영된 최종 상태로 복구")
    void aofReplayOnRestartAppliesDelFlushAndSkipsExpired() throws Exception {
        ConcurrentHashMap store = new ConcurrentHashMap();
        aof = new AOFManager(store, dataDir, mapper);
        aof.init();

        set(store, "gone", "x", 0);
        store.clear();
        aof.appendFlush();
        set(store, "a", "1", 0);
        set(store, "b", "2", 0);
        store.remove("b");
        aof.appendDel("b");
        set(store, "c", "old", 0);
        // 과거 시각에 TTL 1초로 기록된 SET → 이미 만료 → 건너뛰고 c 는 사라져야 함
        aof.appendSet("c", "short", 1, System.currentTimeMillis() - 10_000);
        aof.close();

        // 새 프로세스: 빈 store 로 init() 하면 자동 Replay
        ConcurrentHashMap restarted = new ConcurrentHashMap();
        aof = new AOFManager(restarted, dataDir, mapper);
        aof.init();

        assertThat(restarted.get("gone")).isNull();
        assertThat(restarted.get("a")).isEqualTo("1");
        assertThat(restarted.get("b")).isNull();
        assertThat(restarted.get("c")).isNull();
        assertThat(restarted.size()).isEqualTo(1);
    }

    @Test
    @DisplayName("3. RDB 스냅샷 저장 → clear → RDB 복구 → 데이터 정합성")
    void rdbSnapshotAndRestore() throws Exception {
        ConcurrentHashMap store = new ConcurrentHashMap();
        RDBManager rdb = new RDBManager(store, dataDir, mapper, 0); // 0: 주기 스케줄러 비활성화

        store.put("s1", "v1", 0);
        store.put("s2", 123, 0);
        store.put("s3", Map.of("name", "mini"), 120);
        store.put("expired", "x", 1);
        Thread.sleep(1_100); // expired 키 만료 → 스냅샷에서 제외되어야 함

        int saved = rdb.save();
        assertThat(saved).isEqualTo(3);
        assertThat(Files.exists(dataDir.resolve(RDBManager.RDB_FILE_NAME))).isTrue();

        store.clear();
        assertThat(store.size()).isZero();

        int restored = rdb.restore();
        assertThat(restored).isEqualTo(3);
        assertThat(store.get("s1")).isEqualTo("v1");
        assertThat(store.get("s2")).isEqualTo(123);
        assertThat(store.get("s3")).isEqualTo(Map.of("name", "mini"));
        assertThat(store.get("expired")).isNull();
        assertThat(store.getEntry("s3").remainingTtlSeconds(System.currentTimeMillis())).isBetween(110L, 120L);
    }

    @Test
    @DisplayName("3-1. 시작 시 AOF 가 없고 RDB 만 있으면 RDB 에서 복구 후 AOF 재작성")
    void startupRestoresFromRdbWhenNoAof() throws Exception {
        ConcurrentHashMap original = new ConcurrentHashMap();
        original.put("r1", "one", 0);
        original.put("r2", "two", 300);
        new RDBManager(original, dataDir, mapper, 0).save();
        assertThat(Files.exists(dataDir.resolve(AOFManager.AOF_FILE_NAME))).isFalse();

        // 서버 시작 순서 재현: RDB init → AOF init
        ConcurrentHashMap store = new ConcurrentHashMap();
        RDBManager rdb = new RDBManager(store, dataDir, mapper, 0);
        rdb.init();
        aof = new AOFManager(store, dataDir, mapper);
        aof.init();

        assertThat(store.get("r1")).isEqualTo("one");
        assertThat(store.get("r2")).isEqualTo("two");
        // RDB 로 복구된 상태가 AOF 에도 반영되어 다음 재시작 때 유실되지 않아야 함
        assertThat(Files.readAllLines(dataDir.resolve(AOFManager.AOF_FILE_NAME))).hasSize(2);

        aof.close();
        ConcurrentHashMap again = new ConcurrentHashMap();
        aof = new AOFManager(again, dataDir, mapper);
        aof.init();
        assertThat(again.get("r1")).isEqualTo("one");
        assertThat(again.get("r2")).isEqualTo("two");
    }
}
