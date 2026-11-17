package com.minidbms.dbms.bench;

import com.minidbms.dbms.engine.Database;
import com.minidbms.dbms.engine.QueryResult;
import com.minidbms.dbms.engine.SqlEngine;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Random;

/**
 * Phase 2 성능 테스트: 대량 삽입 후 "인덱스 사용 SELECT" 와 "선형 탐색 SELECT" 의 속도를 비교한다.
 *
 * <p>모든 작업을 SQL 문자열로 만들어 {@link SqlEngine} 에 넣는다.
 * 즉 Phase 1 의 SQL 처리기(파싱 → 실행 → 파일 저장)를 그대로 거친다.</p>
 */
public final class Benchmark {

    private static final String TABLE = "bench_users";
    /** INSERT 문 하나에 담는 행 수 (multi-row INSERT) */
    private static final int BATCH_SIZE = 1_000;

    private Benchmark() {
    }

    public static void run(Path dataDir, long rows, PrintStream out) throws IOException {
        // 이전 벤치마크가 남긴 파일만 지운다 (같은 폴더의 다른 테이블은 건드리지 않는다)
        Files.createDirectories(dataDir);
        Files.deleteIfExists(dataDir.resolve(TABLE + ".schema"));
        Files.deleteIfExists(dataDir.resolve(TABLE + ".tbl"));

        out.println("== Mini DBMS benchmark ==");
        out.println("rows: " + String.format(Locale.ROOT, "%,d", rows) + ", data dir: " + dataDir.toAbsolutePath());
        out.println();

        try (Database database = new Database(dataDir)) {
            SqlEngine engine = new SqlEngine(database);
            engine.execute("CREATE TABLE " + TABLE + " (name TEXT, age INT, email TEXT)");

            // ---- 1) 대량 삽입 ----
            long start = System.nanoTime();
            StringBuilder sql = new StringBuilder(BATCH_SIZE * 64);
            for (long i = 1; i <= rows; ) {
                sql.setLength(0);
                sql.append("INSERT INTO ").append(TABLE).append(" (name, age, email) VALUES ");
                for (int b = 0; b < BATCH_SIZE && i <= rows; b++, i++) {
                    if (b > 0) {
                        sql.append(',');
                    }
                    sql.append("('user").append(i).append("',").append(18 + i % 60)
                            .append(",'user").append(i).append("@example.com')");
                }
                engine.execute(sql.toString());
            }
            double insertSeconds = (System.nanoTime() - start) / 1e9;
            out.printf(Locale.ROOT, "[INSERT] %,d rows in %.2f s  (%,.0f rows/s), index height = %d%n%n",
                    rows, insertSeconds, rows / insertSeconds, database.table(TABLE).indexHeight());

            Random random = new Random(42);

            // ---- 2) id 기준 SELECT: B+ 트리 인덱스 사용 ----
            int pointQueries = 10_000;
            warmUp(engine, rows);
            String pointPath = null;
            start = System.nanoTime();
            for (int i = 0; i < pointQueries; i++) {
                long id = 1 + (long) (random.nextDouble() * rows);
                QueryResult r = engine.execute("SELECT * FROM " + TABLE + " WHERE id = " + id);
                check(r.rows().size() == 1, "indexed lookup must find exactly one row");
                pointPath = r.accessPath();
            }
            double indexedMs = (System.nanoTime() - start) / 1e6 / pointQueries;

            // ---- 3) 다른 필드 기준 SELECT: 선형 탐색 ----
            int scanQueries = 5;
            String scanPath = null;
            start = System.nanoTime();
            for (int i = 0; i < scanQueries; i++) {
                long id = 1 + (long) (random.nextDouble() * rows);
                QueryResult r = engine.execute(
                        "SELECT * FROM " + TABLE + " WHERE email = 'user" + id + "@example.com'");
                check(r.rows().size() == 1, "linear scan must find exactly one row");
                scanPath = r.accessPath();
            }
            double scanMs = (System.nanoTime() - start) / 1e6 / scanQueries;

            // ---- 4) 같은 id 조건을 인덱스 없이 (인덱스 끄기) ----
            engine.setIndexEnabled(false);
            String noIndexPath = null;
            start = System.nanoTime();
            for (int i = 0; i < scanQueries; i++) {
                long id = 1 + (long) (random.nextDouble() * rows);
                QueryResult r = engine.execute("SELECT * FROM " + TABLE + " WHERE id = " + id);
                check(r.rows().size() == 1, "non-indexed lookup must find exactly one row");
                noIndexPath = r.accessPath();
            }
            double noIndexMs = (System.nanoTime() - start) / 1e6 / scanQueries;
            engine.setIndexEnabled(true);

            // ---- 5) id 범위 SELECT: 리프 연결을 따라가는 범위 검색 ----
            int rangeQueries = 200;
            long span = Math.min(100, rows);
            String rangePath = null;
            start = System.nanoTime();
            for (int i = 0; i < rangeQueries; i++) {
                long low = 1 + (long) (random.nextDouble() * (rows - span + 1));
                QueryResult r = engine.execute("SELECT * FROM " + TABLE
                        + " WHERE id BETWEEN " + low + " AND " + (low + span - 1));
                check(r.rows().size() == span, "range scan must return the whole range");
                rangePath = r.accessPath();
            }
            double rangeMs = (System.nanoTime() - start) / 1e6 / rangeQueries;

            out.println("[SELECT] average time per query");
            out.println("+----------------------------------------+--------------+-------------+----------+");
            out.println("| query                                  | access path  | avg time    | queries  |");
            out.println("+----------------------------------------+--------------+-------------+----------+");
            printRow(out, "WHERE id = ?            (B+ tree)", pointPath, indexedMs, pointQueries);
            printRow(out, "WHERE id BETWEEN ? AND ? (" + span + " rows)", rangePath, rangeMs, rangeQueries);
            printRow(out, "WHERE email = ?         (other field)", scanPath, scanMs, scanQueries);
            printRow(out, "WHERE id = ?            (index off)", noIndexPath, noIndexMs, scanQueries);
            out.println("+----------------------------------------+--------------+-------------+----------+");
            out.printf(Locale.ROOT, "%nIndexed lookup is %,.0fx faster than a linear scan on another field.%n",
                    scanMs / indexedMs);
        }
    }

    /** JIT 컴파일이 끝난 뒤의 속도를 재기 위해 측정 전에 몇 번 실행해 둔다. */
    private static void warmUp(SqlEngine engine, long rows) {
        for (int i = 0; i < 2_000; i++) {
            engine.execute("SELECT * FROM " + TABLE + " WHERE id = " + (1 + i % rows));
        }
    }

    private static void printRow(PrintStream out, String query, String path, double ms, int queries) {
        out.printf(Locale.ROOT, "| %-38s | %-12s | %8.4f ms | %8d |%n", query, path, ms, queries);
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException("Benchmark sanity check failed: " + message);
        }
    }
}
