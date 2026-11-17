package com.minidbms.server;

import com.minidbms.dbms.engine.QueryResult;
import com.minidbms.dbms.engine.SqlEngine;
import com.minidbms.dbms.sql.Statement;
import com.minidbms.redis.core.ConcurrentHashMap;

import java.util.concurrent.atomic.AtomicLong;

/**
 * DBMS 와 미니 레디스를 잇는 <b>유일한</b> 지점. (Cache-Aside 패턴)
 *
 * <p>yj-mini-redis 의 {@code RedisController.slowDb()} 와 같은 흐름이다.
 * 그때는 {@code Thread.sleep(500)} 으로 느린 DB 를 흉내 냈고, 지금은 그 자리에 진짜 DBMS 가 들어간다.</p>
 *
 * <pre>
 *   yj-mini-redis 의 slowDb()                       여기 (executeSelect)
 *   ---------------------------------------        ---------------------------------------------
 *   Object cached = store.get(cacheKey);            Object cached = store.get(key);
 *   if (cached != null) → 즉시 반환 (Hit)            if (cached != null) → 즉시 반환              (HIT)
 *   data = queryFromSlowDatabase(id);  // 500ms     result = engine.execute(select);  // 파일 전체 스캔
 *   store.put(cacheKey, data, 30);                  store.put(key, result, ttlSeconds);         (MISS)
 * </pre>
 *
 * <p><b>역할 구분</b></p>
 * <ul>
 *   <li>{@link SqlEngine} (DBMS): 진짜 데이터. 디스크에 있고 항상 정확하다. 캐시가 있는지 모른다.</li>
 *   <li>{@link ConcurrentHashMap} (미니 레디스): DBMS 가 계산한 결과의 임시 복사본. 메모리에 있고 언제 사라져도 된다. SQL 을 모른다.</li>
 *   <li>이 클래스: 둘을 언제 어떻게 쓸지 정한다. 캐시를 통째로 비워도 결과는 달라지지 않고 속도만 느려진다.</li>
 * </ul>
 *
 * <p><b>slowDb() 에 없던 것 — 무효화</b><br>
 * slowDb 의 가짜 DB 는 내용이 바뀌지 않았지만, 진짜 DBMS 는 INSERT 로 내용이 바뀐다.
 * 그래서 캐시 키에 테이블의 버전 번호를 넣는다: {@code sql:users:v7:Select[...]}.
 * INSERT 가 끝나면 그 테이블의 버전을 8 로 올린다. 이후 SELECT 는 {@code v8} 키를 찾으므로
 * 옛 결과(v7)는 다시 조회되지 않고, TTL 이 지나면 TTLManager 가 지운다.</p>
 */
public final class CachedQueryService {

    public static final String HIT = "HIT";
    public static final String MISS = "MISS";
    public static final String BYPASS = "BYPASS";

    /** 쿼리 캐시가 쓰는 키 접두사 (/api/redis 로 직접 넣는 키와 구분된다) */
    static final String KEY_PREFIX = "sql:";

    /**
     * @param result 실행 결과
     * @param cache  HIT(캐시에서 반환) / MISS(DBMS 실행 후 캐시에 저장) / BYPASS(캐시와 무관)
     */
    public record Outcome(QueryResult result, String cache) {
    }

    /**
     * 레디스에 넣는 값. QueryResult 를 감싸서, GET /api/redis 목록에서 수천 행이 통째로 출력되는 대신
     * "cached SELECT result: 2 rows" 같은 한 줄로 보이게 한다.
     */
    private record CachedResult(QueryResult result) {
        @Override
        public String toString() {
            return "cached SELECT result: " + result.message();
        }
    }

    private final SqlEngine engine;
    private final ConcurrentHashMap store;
    private final int ttlSeconds;
    private final java.util.concurrent.ConcurrentHashMap<String, AtomicLong> tableVersions =
            new java.util.concurrent.ConcurrentHashMap<>();

    private final AtomicLong hits = new AtomicLong();
    private final AtomicLong misses = new AtomicLong();
    private final AtomicLong bypassed = new AtomicLong();

    /**
     * @param store      미니 레디스 저장소
     * @param ttlSeconds 쿼리 결과를 캐시에 둘 시간(초). 0 이하면 쿼리 캐시를 쓰지 않는다
     */
    public CachedQueryService(SqlEngine engine, ConcurrentHashMap store, int ttlSeconds) {
        this.engine = engine;
        this.store = store;
        this.ttlSeconds = ttlSeconds;
    }

    public Outcome execute(Statement statement) {
        if (statement instanceof Statement.Select select && ttlSeconds > 0) {
            return executeSelect(select);
        }
        if (statement instanceof Statement.Insert insert) {
            try {
                return bypass(engine.execute(insert));
            } finally {
                // 데이터가 바뀌었으니 이 테이블의 캐시된 결과는 더 이상 쓰면 안 된다
                version(insert.table()).incrementAndGet();
            }
        }
        return bypass(engine.execute(statement));
    }

    private Outcome executeSelect(Statement.Select select) {
        if (!engine.database().hasTable(select.table())) {
            // 없는 테이블: 버전 번호를 만들지 않고 DBMS 가 오류를 내게 둔다
            // (아무 이름이나 보내는 요청으로 버전 맵이 끝없이 커지는 것을 막는다)
            return bypass(engine.execute(select));
        }
        long start = System.nanoTime();
        // record 의 toString() 은 공백/대소문자가 달라도 같은 쿼리면 같은 문자열이 된다
        String key = KEY_PREFIX + select.table() + ":v" + version(select.table()).get() + ":" + select;

        // 1) 캐시 조회 → Hit 이면 즉시 반환 (DBMS 를 건드리지 않는다)
        if (store.get(key) instanceof CachedResult cached) {
            hits.incrementAndGet();
            double elapsedMs = (System.nanoTime() - start) / 1_000_000.0;
            return new Outcome(cached.result().withElapsedMs(elapsedMs), HIT);
        }

        // 2) Miss 이면 DBMS 조회 (없는 컬럼 같은 오류는 그대로 던져지고, 아무것도 캐시되지 않는다)
        QueryResult result = engine.execute(select);

        // 3) 파일 전체를 읽어야 했던 느린 쿼리만 TTL 을 걸어 캐싱한다. 인덱스 조회는 이미 충분히 빠르다.
        if (QueryResult.FULL_SCAN.equals(result.accessPath())) {
            store.put(key, new CachedResult(result), ttlSeconds);
            misses.incrementAndGet();
            return new Outcome(result, MISS);
        }
        return bypass(result);
    }

    private Outcome bypass(QueryResult result) {
        bypassed.incrementAndGet();
        return new Outcome(result, BYPASS);
    }

    private AtomicLong version(String table) {
        return tableVersions.computeIfAbsent(table, t -> new AtomicLong());
    }

    public boolean enabled() {
        return ttlSeconds > 0;
    }

    public int ttlSeconds() {
        return ttlSeconds;
    }

    public long hits() {
        return hits.get();
    }

    public long misses() {
        return misses.get();
    }

    public long bypassed() {
        return bypassed.get();
    }
}
