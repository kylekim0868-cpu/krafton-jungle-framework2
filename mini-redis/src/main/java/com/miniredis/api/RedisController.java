package com.miniredis.api;

import com.miniredis.core.ConcurrentHashMap;
import com.miniredis.core.HashTable;
import com.miniredis.core.TTLManager;
import com.miniredis.persistence.AOFManager;
import com.miniredis.persistence.RDBManager;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Mini Redis REST API.
 *
 * <pre>
 *  POST   /api/redis            저장/수정       {"key":"k1","value":"v1","ttl":60}
 *  GET    /api/redis/{key}      단건 조회
 *  DELETE /api/redis/{key}      단건 삭제
 *  GET    /api/redis            전체 목록 + 통계 + 버킷 분포
 *  DELETE /api/redis            전체 초기화(FLUSH)
 *  POST   /api/redis/save       RDB 스냅샷 수동 저장
 * </pre>
 */
@RestController
@RequestMapping("/api")
public class RedisController {

    private final ConcurrentHashMap store;
    private final TTLManager ttlManager;
    private final AOFManager aofManager;
    private final RDBManager rdbManager;

    public RedisController(ConcurrentHashMap store, TTLManager ttlManager,
                           AOFManager aofManager, RDBManager rdbManager) {
        this.store = store;
        this.ttlManager = ttlManager;
        this.aofManager = aofManager;
        this.rdbManager = rdbManager;
    }

    /** POST /api/redis 요청 본문. value 는 문자열/숫자/객체 등 임의의 JSON 값 허용. */
    public record SetRequest(String key, Object value, Integer ttl) {
    }

    // =====================================================================
    // CRUD
    // =====================================================================

    @PostMapping("/redis")
    public ResponseEntity<Map<String, Object>> set(@RequestBody(required = false) SetRequest req) {
        if (req == null) {
            return error(HttpStatus.BAD_REQUEST, "request body is required");
        }
        if (req.key() == null || req.key().isBlank()) {
            return error(HttpStatus.BAD_REQUEST, "key is required");
        }
        if (req.value() == null) {
            return error(HttpStatus.BAD_REQUEST, "value is required");
        }
        int ttl = req.ttl() == null ? 0 : req.ttl();
        String key = req.key().trim();

        setAndLog(key, req.value(), ttl);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("key", key);
        body.put("value", req.value());
        body.put("ttl", ttl > 0 ? ttl : -1);
        body.put("remainingTTL", ttl > 0 ? ttl : -1);
        return ResponseEntity.status(HttpStatus.CREATED).body(body);
    }

    @GetMapping("/redis/{key}")
    public ResponseEntity<Map<String, Object>> get(@PathVariable String key) {
        // getEntry 내부에서 Lazy Expiration 이 수행됨
        HashTable.EntryView entry = store.getEntry(key);
        if (entry == null) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("error", "key not found or expired");
            body.put("key", key);
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(body);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("key", entry.key());
        body.put("value", entry.value());
        body.put("remainingTTL", entry.remainingTtlSeconds(System.currentTimeMillis()));
        return ResponseEntity.ok(body);
    }

    @DeleteMapping("/redis/{key}")
    public ResponseEntity<Map<String, Object>> delete(@PathVariable String key) {
        if (!store.remove(key)) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("error", "key not found or expired");
            body.put("key", key);
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(body);
        }
        aofManager.appendDel(key); // 삭제 성공 시에만 AOF 기록
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("message", "deleted");
        body.put("key", key);
        return ResponseEntity.ok(body);
    }

    @GetMapping("/redis")
    public ResponseEntity<Map<String, Object>> list() {
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
        stats.put("aofFileBytes", aofManager.getFileSize());
        stats.put("rdbLastSaveAt", rdbManager.getLastSaveAt());
        stats.put("rdbLastSaveCount", rdbManager.getLastSaveCount());

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("count", entries.size());
        body.put("entries", entries);
        body.put("buckets", buckets);
        body.put("segments", store.getSegmentStats());
        body.put("stats", stats);
        return ResponseEntity.ok(body);
    }

    @DeleteMapping("/redis")
    public ResponseEntity<Map<String, Object>> flush() {
        store.clear();
        aofManager.appendFlush();
        return ResponseEntity.ok(Map.of("message", "flushed"));
    }

    /** RDB 스냅샷 수동 저장 (Redis 의 SAVE 명령) */
    @PostMapping("/redis/save")
    public ResponseEntity<Map<String, Object>> save() {
        int count = rdbManager.save();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("message", "saved");
        body.put("count", count);
        body.put("file", rdbManager.getRdbFile().getFileName().toString());
        return ResponseEntity.ok(body);
    }

    // =====================================================================
    // 공통
    // =====================================================================

    /** 메모리 반영 후 AOF 에 기록 (put 성공 시에만 기록) */
    private void setAndLog(String key, Object value, int ttl) {
        store.put(key, value, ttl);
        aofManager.appendSet(key, value, ttl);
    }

    private static ResponseEntity<Map<String, Object>> error(HttpStatus status, String message) {
        return ResponseEntity.status(status).body(Map.of("error", message));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleIllegalArgument(IllegalArgumentException e) {
        return error(HttpStatus.BAD_REQUEST, e.getMessage());
    }
}
