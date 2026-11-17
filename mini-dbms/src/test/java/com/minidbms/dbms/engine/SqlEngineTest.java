package com.minidbms.dbms.engine;

import com.minidbms.dbms.DbException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** SQL 문자열 → 파싱 → 실행 → 파일 저장까지 전체 흐름을 검증하는 기능 테스트. */
class SqlEngineTest {

    @TempDir
    Path dir;

    private Database database;
    private SqlEngine engine;

    @BeforeEach
    void setUp() {
        open();
        engine.execute("CREATE TABLE users (name TEXT, age INT, score REAL)");
    }

    @AfterEach
    void tearDown() {
        database.close();
    }

    private void open() {
        database = new Database(dir);
        engine = new SqlEngine(database);
    }

    private void reopen() {
        database.close();
        open();
    }

    private List<Object[]> rows(String sql) {
        return engine.execute(sql).rows();
    }

    private void insertSample() {
        engine.execute("INSERT INTO users VALUES ('kim', 20, 3.5), ('lee', 30, 4.0), ('park', 40, NULL), "
                + "('choi', 30, 2.25), ('jung', NULL, 1.0)");
    }

    // ---------------- INSERT ----------------

    @Test
    @DisplayName("INSERT: id 가 1부터 자동으로 부여된다")
    void autoIncrementId() {
        QueryResult first = engine.execute("INSERT INTO users VALUES ('kim', 20, 3.5)");
        QueryResult second = engine.execute("INSERT INTO users (age, name) VALUES (30, 'lee'), (40, 'park')");

        assertEquals(1L, first.firstInsertId());
        assertEquals(1, first.affected());
        assertEquals(2L, second.firstInsertId());
        assertEquals(2, second.affected());

        List<Object[]> all = rows("SELECT * FROM users");
        assertEquals(3, all.size());
        assertArrayEquals(new Object[]{1L, "kim", 20L, 3.5}, all.get(0));
        assertArrayEquals(new Object[]{2L, "lee", 30L, null}, all.get(1));   // 지정 안 한 컬럼은 NULL
        assertArrayEquals(new Object[]{3L, "park", 40L, null}, all.get(2));
    }

    @Test
    @DisplayName("INSERT: 정수 리터럴은 REAL 컬럼에 들어가고, 특수 문자가 든 문자열도 그대로 보존된다")
    void insertCoercionAndSpecialCharacters() {
        engine.execute("INSERT INTO users VALUES ('O''Brien, \"Jr.\"\nline2', 1, 5)");
        Object[] row = rows("SELECT * FROM users WHERE id = 1").get(0);
        assertEquals("O'Brien, \"Jr.\"\nline2", row[1]);
        assertEquals(5.0, row[3]);
    }

    @Test
    @DisplayName("INSERT 오류: 개수 불일치, 타입 불일치, 없는 컬럼, id 직접 지정, 중복 컬럼")
    void insertErrors() {
        assertThrows(DbException.class, () -> engine.execute("INSERT INTO users VALUES ('kim', 20)"));
        assertThrows(DbException.class, () -> engine.execute("INSERT INTO users VALUES (20, 'kim', 1.0)"));
        assertThrows(DbException.class, () -> engine.execute("INSERT INTO users VALUES ('kim', 1.5, 1.0)"));
        assertThrows(DbException.class, () -> engine.execute("INSERT INTO users (nope) VALUES (1)"));
        assertThrows(DbException.class, () -> engine.execute("INSERT INTO users (id, name) VALUES (9, 'x')"));
        assertThrows(DbException.class, () -> engine.execute("INSERT INTO users (name, name) VALUES ('a', 'b')"));
        assertThrows(DbException.class, () -> engine.execute("INSERT INTO nope VALUES (1)"));
    }

    @Test
    @DisplayName("INSERT: 여러 행 중 하나라도 잘못되면 한 행도 저장되지 않는다")
    void multiRowInsertIsAllOrNothing() {
        assertThrows(DbException.class,
                () -> engine.execute("INSERT INTO users VALUES ('ok', 1, 1.0), ('bad', 'x', 1.0)"));
        assertEquals(0L, rows("SELECT COUNT(*) FROM users").get(0)[0]);
        // 실패한 문장이 id 를 소모하지 않는다
        assertEquals(1L, engine.execute("INSERT INTO users VALUES ('next', 1, 1.0)").firstInsertId());
    }

    // ---------------- SELECT ----------------

    @Test
    @DisplayName("SELECT: 없는 id, id 조건 + 다른 조건, 모순된 조건")
    void indexEdgeCases() {
        insertSample();
        assertTrue(rows("SELECT * FROM users WHERE id = 999").isEmpty());
        assertTrue(rows("SELECT * FROM users WHERE id = 0").isEmpty());
        assertTrue(rows("SELECT * FROM users WHERE id = -1").isEmpty());
        assertEquals(1, rows("SELECT * FROM users WHERE id = 2 AND name = 'lee'").size());
        assertTrue(rows("SELECT * FROM users WHERE id = 2 AND name = 'kim'").isEmpty());
        assertTrue(rows("SELECT * FROM users WHERE id = 2 AND id = 3").isEmpty());
        assertTrue(rows("SELECT * FROM users WHERE id > 3 AND id < 2").isEmpty());
        assertTrue(rows("SELECT * FROM users WHERE id > 9223372036854775807").isEmpty());
        assertEquals(List.of(4L, 5L), ids(engine.execute("SELECT id FROM users WHERE id > 3")));
        assertEquals(List.of(1L, 2L), ids(engine.execute("SELECT id FROM users WHERE id <= 2")));
    }

    @Test
    @DisplayName("SELECT: 비교 연산자, NULL 처리, COUNT(*), LIMIT")
    void filters() {
        insertSample();
        assertEquals(List.of(2L, 4L), ids(engine.execute("SELECT id FROM users WHERE age = 30")));
        assertEquals(List.of(1L, 3L), ids(engine.execute("SELECT id FROM users WHERE age <> 30")));   // NULL 은 제외
        assertEquals(List.of(2L, 3L, 4L), ids(engine.execute("SELECT id FROM users WHERE age >= 30")));
        assertEquals(List.of(1L, 2L), ids(engine.execute("SELECT id FROM users WHERE score > 3")));    // REAL vs 정수
        assertEquals(List.of(5L), ids(engine.execute("SELECT id FROM users WHERE age IS NULL")));
        assertEquals(List.of(1L, 2L, 4L, 5L), ids(engine.execute("SELECT id FROM users WHERE score IS NOT NULL")));
        assertTrue(rows("SELECT id FROM users WHERE age = NULL").isEmpty());
        assertEquals(List.of(1L, 4L, 5L), ids(engine.execute("SELECT id FROM users WHERE name < 'l'")));  // kim, choi, jung

        assertEquals(5L, rows("SELECT COUNT(*) FROM users").get(0)[0]);
        assertEquals(2L, rows("SELECT COUNT(*) FROM users WHERE age = 30").get(0)[0]);
        assertEquals(0L, rows("SELECT COUNT(*) FROM users WHERE id = 99").get(0)[0]);

        assertEquals(List.of(1L, 2L), ids(engine.execute("SELECT id FROM users LIMIT 2")));
        assertEquals(List.of(2L), ids(engine.execute("SELECT id FROM users WHERE age = 30 LIMIT 1")));
        assertTrue(rows("SELECT id FROM users LIMIT 0").isEmpty());
    }

    @Test
    @DisplayName("SELECT 오류: 없는 테이블/컬럼, 타입이 맞지 않는 비교")
    void selectErrors() {
        assertThrows(DbException.class, () -> engine.execute("SELECT * FROM nope"));
        assertThrows(DbException.class, () -> engine.execute("SELECT nope FROM users"));
        assertThrows(DbException.class, () -> engine.execute("SELECT * FROM users WHERE nope = 1"));
        assertThrows(DbException.class, () -> engine.execute("SELECT * FROM users WHERE age = 'x'"));
        assertThrows(DbException.class, () -> engine.execute("SELECT * FROM users WHERE name = 1"));
    }

    @Test
    @DisplayName("최대 행 수 제한에 걸리면 truncated 표시")
    void maxResultRows() {
        insertSample();
        engine.setMaxResultRows(3);
        QueryResult result = engine.execute("SELECT * FROM users");
        assertEquals(3, result.rows().size());
        assertTrue(result.truncated());
        assertEquals(5L, rows("SELECT COUNT(*) FROM users").get(0)[0]); // COUNT 는 제한과 무관
    }

    // ---------------- CREATE ----------------

    @Test
    @DisplayName("CREATE TABLE: 중복 생성은 오류, IF NOT EXISTS 는 건너뜀, id 컬럼 선언 금지")
    void createTable() {
        assertThrows(DbException.class, () -> engine.execute("CREATE TABLE users (x INT)"));
        engine.execute("CREATE TABLE IF NOT EXISTS users (x INT)");
        assertEquals(4, database.table("users").schema().size()); // 기존 구조 유지
        assertThrows(DbException.class, () -> engine.execute("CREATE TABLE t2 (id INT, x INT)"));
        assertThrows(DbException.class, () -> engine.execute("CREATE TABLE t3 (x INT, x TEXT)"));
    }

    // ---------------- 영속성 ----------------

    @Test
    @DisplayName("재시작: 파일에서 데이터를 다시 읽고, id 가 이어진다")
    void persistenceAcrossRestart() {
        insertSample();
        reopen();

        assertEquals(5L, rows("SELECT COUNT(*) FROM users").get(0)[0]);
        QueryResult byId = engine.execute("SELECT name FROM users WHERE id = 4");
        assertArrayEquals(new Object[]{"choi"}, byId.rows().get(0));

        assertEquals(6L, engine.execute("INSERT INTO users VALUES ('new', 1, 1.0)").firstInsertId());
        reopen();
        assertArrayEquals(new Object[]{6L, "new", 1L, 1.0}, rows("SELECT * FROM users WHERE id = 6").get(0));
    }

    @Test
    @DisplayName("쓰다가 죽어서 마지막 줄이 잘린 파일: 잘린 조각만 버리고 정상 복구한다")
    void tornTailIsDiscarded() throws IOException {
        insertSample();
        database.close();
        Path dataFile = dir.resolve("users.tbl");
        long goodSize = Files.size(dataFile);
        Files.writeString(dataFile, "6,half-writ", StandardCharsets.UTF_8, StandardOpenOption.APPEND);

        open();
        assertEquals(5L, rows("SELECT COUNT(*) FROM users").get(0)[0]);
        assertEquals(goodSize, Files.size(dataFile));
        assertEquals(6L, engine.execute("INSERT INTO users VALUES ('six', 6, 6.0)").firstInsertId());
        assertArrayEquals(new Object[]{6L, "six", 6L, 6.0}, rows("SELECT * FROM users WHERE id = 6").get(0));
    }

    @Test
    @DisplayName("청크 경계를 넘는 긴 행과 많은 행도 정확히 읽는다")
    void largeRowsAcrossChunkBoundaries() {
        engine.execute("CREATE TABLE docs (body TEXT)");
        String big = "x".repeat(70_000); // 스캔 버퍼(64KB)보다 큰 행
        engine.execute("INSERT INTO docs VALUES ('" + big + "')");
        StringBuilder sql = new StringBuilder("INSERT INTO docs VALUES ");
        for (int i = 2; i <= 5_000; i++) {
            sql.append(i > 2 ? "," : "").append("('row").append(i).append("')");
        }
        engine.execute(sql.toString());

        assertEquals(big, rows("SELECT body FROM docs WHERE id = 1").get(0)[0]);
        assertEquals("row4321", rows("SELECT body FROM docs WHERE id = 4321").get(0)[0]);
        assertArrayEquals(new Object[]{4321L}, rows("SELECT id FROM docs WHERE body = 'row4321'").get(0));
        assertEquals(5_000L, rows("SELECT COUNT(*) FROM docs WHERE id >= 1").get(0)[0]);

        reopen();
        assertEquals(big, rows("SELECT body FROM docs WHERE id = 1").get(0)[0]);
        assertEquals(5_000L, rows("SELECT COUNT(*) FROM docs WHERE body IS NOT NULL").get(0)[0]);
    }

    @Test
    @DisplayName("스크립트 실행: 여러 문장의 결과가 순서대로 나온다")
    void executeScript() {
        List<QueryResult> results = engine.executeScript(
                "INSERT INTO users VALUES ('a', 1, 1.0); INSERT INTO users VALUES ('b', 2, 2.0);\n"
                        + "SELECT COUNT(*) FROM users;");
        assertEquals(3, results.size());
        assertEquals("INSERT", results.get(0).type());
        assertNull(results.get(2).firstInsertId());
        assertEquals(2L, results.get(2).rows().get(0)[0]);
    }

    private static List<Long> ids(QueryResult result) {
        List<Long> ids = new ArrayList<>();
        for (Object[] row : result.rows()) {
            ids.add((Long) row[0]);
        }
        return ids;
    }
}
