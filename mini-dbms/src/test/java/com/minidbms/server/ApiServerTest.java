package com.minidbms.server;

import com.minidbms.dbms.engine.Database;
import com.minidbms.dbms.engine.SqlEngine;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 실제 포트에 서버를 띄우고 HTTP 로 요청을 보내는 기능 테스트. */
@Timeout(60)
class ApiServerTest {

    @TempDir
    Path dir;

    private Database database;
    private ApiServer server;
    private HttpClient client;
    private String baseUrl;

    @BeforeEach
    void setUp() throws IOException {
        database = new Database(dir);
        server = new ApiServer(new SqlEngine(database), 0, 8, 256); // 포트 0 = 빈 포트 자동 선택
        server.start();
        client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
        baseUrl = "http://localhost:" + server.port();
        assertEquals(200, postSql("CREATE TABLE users (name TEXT, age INT)").statusCode());
    }

    @AfterEach
    void tearDown() {
        server.close();
        database.close();
    }

    private HttpResponse<String> postSql(String sql) {
        return send(HttpRequest.newBuilder(URI.create(baseUrl + "/query"))
                .header("Content-Type", "text/plain; charset=utf-8")
                .POST(HttpRequest.BodyPublishers.ofString(sql, StandardCharsets.UTF_8)).build());
    }

    private HttpResponse<String> get(String path) {
        return send(HttpRequest.newBuilder(URI.create(baseUrl + path)).GET().build());
    }

    private HttpResponse<String> send(HttpRequest request) {
        try {
            return client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new AssertionError("HTTP request failed: " + e, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> json(HttpResponse<String> response) {
        return (Map<String, Object>) Json.parse(response.body());
    }

    /** 응답의 results[index] */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> result(HttpResponse<String> response, int index) {
        return ((List<Map<String, Object>>) json(response).get("results")).get(index);
    }

    // ------------------------------------------------------------------

    @Test
    @DisplayName("GET /health, /tables, /stats")
    void infoEndpoints() {
        HttpResponse<String> health = get("/health");
        assertEquals(200, health.statusCode());
        assertEquals("ok", json(health).get("status"));
        assertTrue(health.headers().firstValue("Content-Type").orElse("").contains("application/json"));

        Map<String, Object> tables = json(get("/tables"));
        Map<?, ?> users = (Map<?, ?>) ((List<?>) tables.get("tables")).get(0);
        assertEquals("users", users.get("name"));
        assertEquals(3, ((List<?>) users.get("columns")).size()); // id + name + age

        Map<String, Object> stats = json(get("/stats"));
        assertEquals(8L, stats.get("threads"));
        assertEquals(256L, stats.get("queueCapacity"));
    }

    @Test
    @DisplayName("POST /query: INSERT 후 SELECT, 인덱스 사용 여부가 응답에 나온다")
    void insertThenSelect() {
        HttpResponse<String> insert = postSql("INSERT INTO users VALUES ('kim', 20), ('lee', 30)");
        assertEquals(200, insert.statusCode());
        assertEquals(true, json(insert).get("ok"));
        assertEquals(2L, result(insert, 0).get("affected"));
        assertEquals(1L, result(insert, 0).get("firstInsertId"));
        assertEquals(2L, result(insert, 0).get("lastInsertId"));

        Map<String, Object> byId = result(postSql("SELECT * FROM users WHERE id = 2"), 0);
        assertEquals("INDEX_POINT", byId.get("accessPath"));
        assertEquals(List.of("id", "name", "age"), byId.get("columns"));
        assertEquals(List.of(List.of(2L, "lee", 30L)), byId.get("rows"));

        Map<String, Object> byName = result(postSql("SELECT id FROM users WHERE name = 'kim'"), 0);
        assertEquals("FULL_SCAN", byName.get("accessPath"));
        assertEquals(1L, byName.get("rowCount"));
    }

    @Test
    @DisplayName("POST /query: JSON 본문 {\"sql\": ...} 과 여러 문장, 한글/특수 문자")
    void jsonBodyAndMultipleStatements() {
        String sql = "INSERT INTO users VALUES ('김\"철수\"\\n', 7); SELECT name FROM users WHERE id = 1;";
        HttpResponse<String> response = send(HttpRequest.newBuilder(URI.create(baseUrl + "/query"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(Json.write(Map.of("sql", sql)), StandardCharsets.UTF_8))
                .build());

        assertEquals(200, response.statusCode());
        assertEquals("INSERT", result(response, 0).get("type"));
        assertEquals(List.of(List.of("김\"철수\"\\n")), result(response, 1).get("rows"));
    }

    @Test
    @DisplayName("오류 응답: 문법 오류/없는 테이블 400, 빈 본문 400, 잘못된 JSON 400, 404, 405")
    void errorResponses() {
        HttpResponse<String> syntax = postSql("SELEC * FROM users");
        assertEquals(400, syntax.statusCode());
        assertEquals(false, json(syntax).get("ok"));
        assertTrue(json(syntax).get("error").toString().contains("Syntax error"));

        assertEquals(400, postSql("SELECT * FROM nope").statusCode());
        assertEquals(400, postSql("   ").statusCode());
        assertEquals(400, postSql("-- nothing here").statusCode());

        // 두 번째 문장에서 실패: 첫 문장은 이미 반영됐음을 알려 준다
        HttpResponse<String> partial = postSql("INSERT INTO users VALUES ('a', 1); SELECT * FROM nope;");
        assertEquals(400, partial.statusCode());
        assertEquals(1L, json(partial).get("completedStatements"));

        HttpResponse<String> badJson = send(HttpRequest.newBuilder(URI.create(baseUrl + "/query"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"sql\": 5}")).build());
        assertEquals(400, badJson.statusCode());

        assertEquals(404, get("/nope").statusCode());
        assertEquals(405, get("/query").statusCode());
        assertEquals(405, send(HttpRequest.newBuilder(URI.create(baseUrl + "/health"))
                .POST(HttpRequest.BodyPublishers.ofString("x")).build()).statusCode());

        // 오류가 난 뒤에도 서버는 계속 정상 동작한다
        assertEquals(200, get("/health").statusCode());
    }

    @Test
    @DisplayName("HTTP 가 아닌 엉터리 입력을 보내도 서버가 죽지 않는다")
    void malformedRequestDoesNotKillServer() throws IOException {
        try (Socket socket = new Socket("localhost", server.port())) {
            OutputStream out = socket.getOutputStream();
            out.write("this is not http\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
            out.flush();
            InputStream in = socket.getInputStream();
            String response = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(response.startsWith("HTTP/1.1 400"), response);
        }
        try (Socket socket = new Socket("localhost", server.port())) {
            // 연결만 하고 아무것도 보내지 않고 끊는다
            assertFalse(socket.isClosed());
        }
        assertEquals(200, get("/health").statusCode());
    }

    // ------------------------------------------------------------------
    // 미니 레디스 (/api/redis) + 쿼리 캐시
    // ------------------------------------------------------------------

    private HttpResponse<String> redisRequest(String method, String path, String jsonBody) {
        HttpRequest.BodyPublisher publisher = jsonBody == null
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8);
        return send(HttpRequest.newBuilder(URI.create(baseUrl + path))
                .header("Content-Type", "application/json")
                .method(method, publisher).build());
    }

    @Test
    @DisplayName("쿼리 캐시: 느린 SELECT 는 MISS → HIT, INSERT 뒤에는 다시 MISS 이고 최신 결과를 준다")
    void queryCacheThroughApi() {
        postSql("INSERT INTO users VALUES ('kim', 20), ('lee', 30)");
        String sql = "SELECT name FROM users WHERE age >= 20";

        Map<String, Object> first = result(postSql(sql), 0);
        Map<String, Object> second = result(postSql(sql), 0);
        assertEquals("MISS", first.get("cache"));
        assertEquals("HIT", second.get("cache"));
        assertEquals(first.get("rows"), second.get("rows"));

        assertEquals("BYPASS", result(postSql("SELECT * FROM users WHERE id = 1"), 0).get("cache"));

        postSql("INSERT INTO users VALUES ('park', 40)");
        Map<String, Object> third = result(postSql(sql), 0);
        assertEquals("MISS", third.get("cache"));
        assertEquals(List.of(List.of("kim"), List.of("lee"), List.of("park")), third.get("rows"));

        Map<?, ?> queryCache = (Map<?, ?>) json(get("/stats")).get("queryCache");
        assertEquals(true, queryCache.get("enabled"));
        assertEquals(1L, queryCache.get("hits"));
        assertEquals(2L, queryCache.get("misses"));

        // 캐시된 결과는 미니 레디스 목록에서 "sql:" 로 시작하는 키로 보인다
        Map<String, Object> listing = json(redisRequest("GET", "/api/redis", null));
        assertTrue(listing.get("entries").toString().contains("sql:users:"), listing.toString());
    }

    @Test
    @DisplayName("/api/redis: POST → GET → DELETE → GET(404) 전체 CRUD 흐름 (yj-mini-redis 와 같은 응답)")
    void redisCrud() {
        HttpResponse<String> set = redisRequest("POST", "/api/redis",
                "{\"key\":\"session:42\",\"value\":\"로그인 토큰 abc\",\"ttl\":60}");
        assertEquals(201, set.statusCode());
        assertEquals("session:42", json(set).get("key"));
        assertEquals(60L, json(set).get("ttl"));

        Map<String, Object> got = json(redisRequest("GET", "/api/redis/session%3A42", null));
        assertEquals("로그인 토큰 abc", got.get("value"));
        long remaining = (Long) got.get("remainingTTL");
        assertTrue(remaining > 0 && remaining <= 60, "remainingTTL=" + remaining);

        // TTL 없이 저장하면 만료 없음(-1). 값은 문자열이 아닌 JSON 도 된다
        assertEquals(-1L, json(redisRequest("POST", "/api/redis", "{\"key\":\"n\",\"value\":{\"a\":[1,2]}}")).get("ttl"));
        Map<String, Object> plain = json(redisRequest("GET", "/api/redis/n", null));
        assertEquals(-1L, plain.get("remainingTTL"));
        assertEquals(Map.of("a", List.of(1L, 2L)), plain.get("value"));

        assertEquals(200, redisRequest("DELETE", "/api/redis/n", null).statusCode());
        assertEquals(404, redisRequest("DELETE", "/api/redis/n", null).statusCode());
        assertEquals(404, redisRequest("GET", "/api/redis/n", null).statusCode());
        assertEquals(404, redisRequest("GET", "/api/redis/never-set", null).statusCode());

        // 캐시에 무엇을 넣어도 DBMS 의 테이블에는 아무 변화가 없다
        assertEquals(List.of(List.of(0L)), result(postSql("SELECT COUNT(*) FROM users"), 0).get("rows"));
    }

    @Test
    @DisplayName("/api/redis: key 누락 / value 누락 / 잘못된 JSON / 잘못된 ttl → 400")
    void redisBadRequests() {
        assertEquals(400, redisRequest("POST", "/api/redis", "{\"value\":\"v\"}").statusCode());
        assertEquals(400, redisRequest("POST", "/api/redis", "{\"key\":\"  \",\"value\":\"v\"}").statusCode());
        assertEquals(400, redisRequest("POST", "/api/redis", "{\"key\":\"k\"}").statusCode());
        assertEquals(400, redisRequest("POST", "/api/redis", "{not json").statusCode());
        assertEquals(400, redisRequest("POST", "/api/redis", "").statusCode());
        assertEquals(400, redisRequest("POST", "/api/redis", "{\"key\":\"k\",\"value\":\"v\",\"ttl\":\"x\"}").statusCode());
        assertEquals(400, redisRequest("POST", "/api/redis", "{\"key\":\"k\",\"value\":\"v\",\"ttl\":-5}").statusCode());
        assertEquals(405, redisRequest("PUT", "/api/redis", "{}").statusCode());
    }

    @Test
    @DisplayName("/api/redis: TTL 만료 후 GET → 404")
    void redisTtlExpiry() throws InterruptedException {
        assertEquals(201, redisRequest("POST", "/api/redis", "{\"key\":\"temp\",\"value\":1,\"ttl\":1}").statusCode());
        assertEquals(200, redisRequest("GET", "/api/redis/temp", null).statusCode());
        Thread.sleep(1_300);
        assertEquals(404, redisRequest("GET", "/api/redis/temp", null).statusCode());
    }

    @Test
    @DisplayName("/api/redis: 목록/통계, FLUSH 해도 DBMS 데이터는 그대로이고 쿼리는 다시 MISS 부터 시작한다")
    void redisListAndFlush() {
        postSql("INSERT INTO users VALUES ('kim', 20)");
        String sql = "SELECT name FROM users WHERE age = 20";
        postSql(sql);
        redisRequest("POST", "/api/redis", "{\"key\":\"k\",\"value\":\"v\"}");

        Map<String, Object> listing = json(redisRequest("GET", "/api/redis", null));
        assertEquals(2L, listing.get("count")); // 직접 넣은 키 1개 + 캐시된 쿼리 결과 1개
        Map<?, ?> stats = (Map<?, ?>) listing.get("stats");
        assertEquals(2L, stats.get("size"));
        assertEquals(16L, stats.get("segmentCount"));
        assertTrue(listing.containsKey("buckets") && listing.containsKey("segments"));

        HttpResponse<String> flush = redisRequest("DELETE", "/api/redis", null);
        assertEquals(200, flush.statusCode());
        assertEquals("flushed", json(flush).get("message"));
        assertEquals(0L, json(redisRequest("GET", "/api/redis", null)).get("count"));
        assertEquals(404, redisRequest("GET", "/api/redis/k", null).statusCode());

        Map<String, Object> again = result(postSql(sql), 0);
        assertEquals("MISS", again.get("cache"));
        assertEquals(List.of(List.of("kim")), again.get("rows"));
    }

    @Test
    @DisplayName("과부하: 워커와 대기 큐가 모두 차면 503 으로 즉시 거절하고, 풀리면 다시 정상 처리한다")
    void rejectsWith503WhenPoolIsFull() throws Exception {
        try (ApiServer tiny = new ApiServer(new SqlEngine(database), 0, 1, 1)) {
            tiny.start();
            String url = "http://localhost:" + tiny.port() + "/health";

            // 요청을 보내지 않는 연결 2개: 하나는 유일한 워커를 붙잡고, 하나는 큐(1칸)를 채운다
            try (Socket holdsWorker = new Socket("localhost", tiny.port())) {
                waitUntil(() -> tiny.pool().activeCount() == 1);
                try (Socket fillsQueue = new Socket("localhost", tiny.port())) {
                    waitUntil(() -> tiny.pool().queuedCount() == 1);

                    HttpResponse<String> busy = send(HttpRequest.newBuilder(URI.create(url)).GET().build());
                    assertEquals(503, busy.statusCode());
                    assertEquals(false, json(busy).get("ok"));
                    assertEquals(1, tiny.pool().rejectedCount());
                }
            }

            // 붙잡던 연결이 끊기면 워커가 풀려 다시 응답한다
            waitUntil(() -> tiny.pool().activeCount() == 0 && tiny.pool().queuedCount() == 0);
            assertEquals(200, send(HttpRequest.newBuilder(URI.create(url)).GET().build()).statusCode());
        }
    }

    private static void waitUntil(java.util.function.BooleanSupplier condition) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5_000;
        while (!condition.getAsBoolean()) {
            assertTrue(System.currentTimeMillis() < deadline, "condition not met within 5s");
            Thread.sleep(10);
        }
    }

    @Test
    @DisplayName("SELECT 결과가 너무 크면 최대 행 수에서 잘리고 truncated=true")
    void largeResultIsTruncated() {
        StringBuilder sql = new StringBuilder("INSERT INTO users VALUES ");
        long total = ApiServer.MAX_RESULT_ROWS + 500;
        for (int i = 0; i < total; i++) {
            sql.append(i > 0 ? "," : "").append("('u").append(i).append("',").append(i).append(')');
        }
        assertEquals(200, postSql(sql.toString()).statusCode());

        Map<String, Object> all = result(postSql("SELECT id FROM users"), 0);
        assertEquals(ApiServer.MAX_RESULT_ROWS, all.get("rowCount"));
        assertEquals(true, all.get("truncated"));

        Map<String, Object> count = result(postSql("SELECT COUNT(*) FROM users"), 0);
        assertEquals(List.of(List.of(total)), count.get("rows"));
    }

    @Test
    @DisplayName("동시성: 여러 클라이언트가 동시에 INSERT 해도 id 가 겹치지 않고 한 건도 유실되지 않는다")
    void concurrentInserts() throws Exception {
        int clients = 32;
        int insertsPerClient = 40;
        ExecutorService executor = Executors.newFixedThreadPool(clients);
        Set<Long> ids = ConcurrentHashMap.newKeySet();
        CountDownLatch startGun = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();

        for (int c = 0; c < clients; c++) {
            int clientNo = c;
            futures.add(executor.submit(() -> {
                startGun.await();
                for (int i = 0; i < insertsPerClient; i++) {
                    HttpResponse<String> response =
                            postSql("INSERT INTO users VALUES ('c" + clientNo + "-" + i + "', " + i + ")");
                    assertEquals(200, response.statusCode(), response.body());
                    assertTrue(ids.add((Long) result(response, 0).get("firstInsertId")), "duplicate id");
                }
                return null;
            }));
        }
        startGun.countDown();
        for (Future<?> future : futures) {
            future.get(50, TimeUnit.SECONDS);
        }
        executor.shutdown();

        int total = clients * insertsPerClient;
        assertEquals(total, ids.size());
        // id 는 1..total 을 빈틈없이 채운다
        assertEquals(1L, ids.stream().mapToLong(Long::longValue).min().orElseThrow());
        assertEquals((long) total, ids.stream().mapToLong(Long::longValue).max().orElseThrow());
        assertEquals(List.of(List.of((long) total)),
                result(postSql("SELECT COUNT(*) FROM users WHERE age >= 0"), 0).get("rows")); // 파일 전체 스캔으로 확인
    }

    @Test
    @DisplayName("동시성: INSERT 와 SELECT(인덱스/선형)가 섞여도 오류 없이 일관된 결과를 본다")
    void concurrentReadsAndWrites() throws Exception {
        assertEquals(200, postSql("INSERT INTO users VALUES ('seed', 0)").statusCode());
        int writers = 8;
        int readers = 16;
        int rounds = 30;
        ExecutorService executor = Executors.newFixedThreadPool(writers + readers);
        List<Future<?>> futures = new ArrayList<>();

        for (int w = 0; w < writers; w++) {
            futures.add(executor.submit(() -> {
                for (int i = 0; i < rounds; i++) {
                    assertEquals(200, postSql("INSERT INTO users VALUES ('w', 1), ('w', 1)").statusCode());
                }
                return null;
            }));
        }
        for (int r = 0; r < readers; r++) {
            boolean useIndex = r % 2 == 0;
            futures.add(executor.submit(() -> {
                for (int i = 0; i < rounds; i++) {
                    HttpResponse<String> response = useIndex
                            ? postSql("SELECT * FROM users WHERE id = 1")
                            : postSql("SELECT COUNT(*) FROM users WHERE name = 'w'");
                    assertEquals(200, response.statusCode(), response.body());
                    if (useIndex) {
                        assertEquals(List.of(List.of(1L, "seed", 0L)), result(response, 0).get("rows"));
                    } else {
                        // 한 문장의 두 행은 함께 보이거나 함께 안 보여야 한다 → 항상 짝수
                        long count = (Long) ((List<?>) ((List<?>) result(response, 0).get("rows")).get(0)).get(0);
                        assertEquals(0, count % 2, "saw a half-applied INSERT: " + count);
                    }
                }
                return null;
            }));
        }
        for (Future<?> future : futures) {
            future.get(50, TimeUnit.SECONDS);
        }
        executor.shutdown();

        assertEquals(List.of(List.of((long) writers * rounds * 2 + 1)),
                result(postSql("SELECT COUNT(*) FROM users"), 0).get("rows"));
    }
}
