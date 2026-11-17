package com.minidbms.redis.api;

import com.minidbms.redis.core.ConcurrentHashMap;
import com.minidbms.redis.core.HashTable;
import com.minidbms.redis.core.TTLManager;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Mini Redis REST API. yj-mini-redis 의 RedisController 와 같은 경로, 같은 메서드 이름, 같은 응답 모양이다.
 *
 * <pre>
 *  POST   /api/redis            저장/수정       {"key":"k1","value":"v1","ttl":60}
 *  GET    /api/redis/{key}      단건 조회
 *  DELETE /api/redis/{key}      단건 삭제
 *  GET    /api/redis            전체 목록 + 통계 + 버킷 분포
 *  DELETE /api/redis            전체 초기화(FLUSH)
 * </pre>
 *
 * <p><b>yj-mini-redis 와 달라진 점</b></p>
 * <ul>
 *   <li>Spring 이 없다. {@code @GetMapping} 대신 ApiServer 가 경로를 보고 이 클래스의 메서드를 직접 부른다.
 *       그래서 {@code ResponseEntity} 대신 {@link Response}(상태 코드 + 본문)를 돌려준다.</li>
 *   <li>AOF / RDB 가 없다. 여기서 레디스는 "임시 캐시"라서 꺼지면 사라져도 된다.
 *       오래 남겨야 하는 데이터는 DBMS 가 맡는다.</li>
 *   <li>/api/slow-db 가 없다. 그때는 느린 DB 를 Thread.sleep 으로 흉내 냈지만,
 *       지금은 진짜 DBMS 가 있으므로 같은 Cache-Aside 흐름이 CachedQueryService 에 들어 있다.</li>
 * </ul>
 */
public class RedisController {

    /** HTTP 상태 코드 + JSON 으로 바꿀 본문 (Spring 의 ResponseEntity 역할) */
    public record Response(int status, Map<String, Object> body) {
    }

    /** POST /api/redis 요청 본문. value 는 문자열/숫자/객체 등 임의의 JSON 값 허용. */
    public record SetRequest(String key, Object value, Integer ttl) {
    }

    private final ConcurrentHashMap store;
    private final TTLManager ttlManager;

    public RedisController(ConcurrentHashMap store, TTLManager ttlManager) {
        this.store = store;
        this.ttlManager = ttlManager;
    }

    // =====================================================================
    // CRUD
    // =====================================================================

    /** POST /api/redis */
    public Response set(SetRequest req) {
        if (req == null) {
            return error(400, "request body is required");
        }
        if (req.key() == null || req.key().isBlank()) {
            return error(400, "key is required");
        }
        if (req.value() == null) {
            return error(400, "value is required");
        }
        int ttl = req.ttl() == null ? 0 : req.ttl();
        String key = req.key().trim();

        store.put(key, req.value(), ttl);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("key", key);
        body.put("value", req.value());
        body.put("ttl", ttl > 0 ? ttl : -1);
        body.put("remainingTTL", ttl > 0 ? ttl : -1);
        return new Response(201, body);
    }

    /** GET /api/redis/{key} */
    public Response get(String key) {
        // getEntry 내부에서 Lazy Expiration 이 수행됨
        HashTable.EntryView entry = store.getEntry(key);
        if (entry == null) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("error", "key not found or expired");
            body.put("key", key);
            return new Response(404, body);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("key", entry.key());
        body.put("value", entry.value());
        body.put("remainingTTL", entry.remainingTtlSeconds(System.currentTimeMillis()));
        return new Response(200, body);
    }

    /** DELETE /api/redis/{key} */
    public Response delete(String key) {
        if (!store.remove(key)) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("error", "key not found or expired");
            body.put("key", key);
            return new Response(404, body);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("message", "deleted");
        body.put("key", key);
        return new Response(200, body);
    }

    /** GET /api/redis */
    public Response list() {
        List<Map<String, Object>> entries = store.getAllEntries();
        Map<Integer, List<String>> buckets = store.getBucketDistribution();
        int totalBuckets = buckets.size();

        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("size", entries.size());
        stats.put("totalBuckets", totalBuckets);
        stats.put("segmentCount", store.segmentCount());
        stats.put("loadFactor", totalBuckets == 0 ? 0.0 : Math.round(entries.size() * 1000.0 / totalBuckets) / 1000.0);
        stats.put("usedBuckets", buckets.values().stream().filter(l -> !l.isEmpty()).count());
        stats.put("maxChainLength", buckets.values().stream().mapToInt(List::size).max().orElse(0));
        stats.put("activeExpiredTotal", ttlManager.getTotalExpired());

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("count", entries.size());
        body.put("entries", entries);
        body.put("buckets", buckets);
        body.put("segments", store.getSegmentStats());
        body.put("stats", stats);
        return new Response(200, body);
    }

    /** DELETE /api/redis */
    public Response flush() {
        store.clear();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("message", "flushed");
        return new Response(200, body);
    }

    // =====================================================================
    // 공통
    // =====================================================================

    private static Response error(int status, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", message);
        return new Response(status, body);
    }
}
