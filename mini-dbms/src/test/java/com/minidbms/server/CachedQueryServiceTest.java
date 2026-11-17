package com.minidbms.server;

import com.minidbms.dbms.DbException;
import com.minidbms.dbms.engine.Database;
import com.minidbms.dbms.engine.SqlEngine;
import com.minidbms.dbms.sql.Parser;
import com.minidbms.redis.core.ConcurrentHashMap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** DBMS 와 미니 레디스를 잇는 Cache-Aside 로직 검증. */
class CachedQueryServiceTest {

    @TempDir
    Path dir;

    private Database database;
    private SqlEngine engine;
    private ConcurrentHashMap store;
    private CachedQueryService service;

    @BeforeEach
    void setUp() {
        database = new Database(dir);
        engine = new SqlEngine(database);
        store = new ConcurrentHashMap();
        service = new CachedQueryService(engine, store, 30);
        run("CREATE TABLE users (name TEXT, age INT)");
        run("CREATE TABLE logs (msg TEXT)");
        run("INSERT INTO users VALUES ('kim', 20), ('lee', 30)");
    }

    @AfterEach
    void tearDown() {
        database.close();
    }

    private CachedQueryService.Outcome run(String sql) {
        return service.execute(Parser.parseSingle(sql));
    }

    private static long count(CachedQueryService.Outcome outcome) {
        return (Long) outcome.result().rows().get(0)[0];
    }

    @Test
    @DisplayName("느린 SELECT(FULL_SCAN): 첫 실행은 MISS, 같은 쿼리의 두 번째는 HIT 이고 결과가 같다")
    void missThenHit() {
        CachedQueryService.Outcome first = run("SELECT * FROM users WHERE age >= 20");
        CachedQueryService.Outcome second = run("SELECT * FROM users WHERE age >= 20");

        assertEquals(CachedQueryService.MISS, first.cache());
        assertEquals(CachedQueryService.HIT, second.cache());
        assertEquals(2, second.result().rows().size());
        assertEquals("kim", second.result().rows().get(0)[1]);
        assertEquals(1, service.hits());
        assertEquals(1, service.misses());
        assertEquals(1, store.size()); // 미니 레디스에 항목 하나가 들어 있다
    }

    @Test
    @DisplayName("공백/대소문자만 다른 SQL 은 같은 캐시 항목을 쓰고, 값이 다르면 다른 항목이다")
    void keyIsNormalized() {
        run("SELECT name FROM users WHERE age = 20");
        assertEquals(CachedQueryService.HIT, run("select   NAME from USERS where AGE=20;").cache());
        assertEquals(CachedQueryService.MISS, run("SELECT name FROM users WHERE age = 21").cache());
        assertEquals(CachedQueryService.MISS, run("SELECT name FROM users WHERE age = 20 LIMIT 1").cache());
    }

    @Test
    @DisplayName("인덱스로 끝나는 빠른 SELECT 는 캐시에 저장하지 않는다 (BYPASS)")
    void indexQueriesBypassCache() {
        assertEquals(CachedQueryService.BYPASS, run("SELECT * FROM users WHERE id = 1").cache());
        assertEquals(CachedQueryService.BYPASS, run("SELECT * FROM users WHERE id = 1").cache());
        assertEquals(CachedQueryService.BYPASS, run("SELECT * FROM users WHERE id BETWEEN 1 AND 2").cache());
        assertEquals(CachedQueryService.BYPASS, run("SELECT COUNT(*) FROM users").cache());
        assertEquals(0, store.size());
    }

    @Test
    @DisplayName("INSERT 하면 그 테이블의 캐시가 무효화되어 옛 결과를 돌려주지 않는다")
    void insertInvalidatesTable() {
        String sql = "SELECT COUNT(*) FROM users WHERE age >= 0";
        assertEquals(2, count(run(sql)));
        assertEquals(CachedQueryService.HIT, run(sql).cache());

        run("INSERT INTO users VALUES ('park', 40)");

        CachedQueryService.Outcome after = run(sql);
        assertEquals(CachedQueryService.MISS, after.cache());
        assertEquals(3, count(after)); // 캐시된 2 가 아니라 최신 값
        assertEquals(CachedQueryService.HIT, run(sql).cache());
    }

    @Test
    @DisplayName("다른 테이블에 INSERT 해도 이 테이블의 캐시는 유지된다")
    void invalidationIsPerTable() {
        String sql = "SELECT * FROM users WHERE name = 'kim'";
        run(sql);
        run("INSERT INTO logs VALUES ('hello')");
        assertEquals(CachedQueryService.HIT, run(sql).cache());
    }

    @Test
    @DisplayName("TTL 이 지나면 캐시가 만료되어 DBMS 에서 다시 읽는다")
    void ttlExpiry() throws InterruptedException {
        CachedQueryService shortLived = new CachedQueryService(engine, store, 1); // TTL 1초
        String sql = "SELECT * FROM users WHERE name = 'kim'";
        assertEquals(CachedQueryService.MISS, shortLived.execute(Parser.parseSingle(sql)).cache());
        assertEquals(CachedQueryService.HIT, shortLived.execute(Parser.parseSingle(sql)).cache());
        Thread.sleep(1_200);
        // 스케줄러(TTLManager)가 없어도 조회 시점의 Lazy Expiration 으로 만료가 처리된다
        assertEquals(CachedQueryService.MISS, shortLived.execute(Parser.parseSingle(sql)).cache());
    }

    @Test
    @DisplayName("캐시를 통째로 비워도 결과는 같다 (캐시는 속도만 바꾼다)")
    void flushDoesNotChangeResults() {
        String sql = "SELECT COUNT(*) FROM users WHERE age > 25";
        long before = count(run(sql));
        store.clear();
        CachedQueryService.Outcome after = run(sql);
        assertEquals(CachedQueryService.MISS, after.cache());
        assertEquals(before, count(after));
    }

    @Test
    @DisplayName("오류가 난 쿼리는 캐시되지 않고 매번 오류를 낸다")
    void errorsAreNotCached() {
        assertThrows(DbException.class, () -> run("SELECT * FROM nope WHERE x = 1"));
        assertThrows(DbException.class, () -> run("SELECT * FROM users WHERE nope = 1"));
        assertEquals(0, store.size());
        // 테이블이 나중에 생기면 정상 동작한다
        run("CREATE TABLE nope (x INT)");
        assertEquals(0, run("SELECT * FROM nope WHERE x = 1").result().rows().size());
    }

    @Test
    @DisplayName("TTL 0 이면 쿼리 캐시를 쓰지 않는다")
    void disabledWhenTtlIsZero() {
        CachedQueryService off = new CachedQueryService(engine, store, 0);
        String sql = "SELECT * FROM users WHERE age >= 20";
        assertEquals(CachedQueryService.BYPASS, off.execute(Parser.parseSingle(sql)).cache());
        assertEquals(CachedQueryService.BYPASS, off.execute(Parser.parseSingle(sql)).cache());
        assertEquals(0, store.size());
    }
}
