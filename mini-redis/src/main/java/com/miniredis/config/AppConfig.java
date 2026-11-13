package com.miniredis.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.miniredis.core.ConcurrentHashMap;
import com.miniredis.core.TTLManager;
import com.miniredis.persistence.AOFManager;
import com.miniredis.persistence.RDBManager;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DependsOn;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 핵심 컴포넌트 빈 등록.
 *
 * <p>코어/영속성 클래스는 Spring 에 의존하지 않는 순수 Java 클래스로 두고(단위 테스트에서 new 로 생성 가능),
 * 여기서 설정값을 주입하여 빈으로 등록한다. @Bean 으로 등록해도 @PostConstruct / @PreDestroy 는 정상 호출된다.</p>
 *
 * <p><b>초기화 순서</b> (서버 시작 시 복구 흐름)</p>
 * <ol>
 *   <li>store(ConcurrentHashMap) 생성</li>
 *   <li>rdbManager.init(): AOF 가 없거나 비어 있고 RDB 가 있으면 RDB 에서 복구</li>
 *   <li>aofManager.init(): AOF 가 있으면 Replay, 없는데 RDB 로 복구된 데이터가 있으면 AOF 재작성
 *       → {@code @DependsOn("rdbManager")} 로 RDB 가 먼저 초기화되도록 보장</li>
 *   <li>ttlManager.init(): Active Expiration 스케줄러 시작</li>
 * </ol>
 */
@Configuration
public class AppConfig {

    @Value("${miniredis.data-dir:data}")
    private String dataDir;

    @Value("${miniredis.segments:16}")
    private int segments;

    @Value("${miniredis.initial-capacity-per-segment:4}")
    private int initialCapacityPerSegment;

    @Value("${miniredis.ttl.active-interval-ms:1000}")
    private long ttlIntervalMs;

    @Value("${miniredis.rdb.interval-seconds:30}")
    private long rdbIntervalSeconds;

    private Path dataPath() {
        return Paths.get(dataDir).toAbsolutePath().normalize();
    }

    @Bean
    public ConcurrentHashMap store() {
        return new ConcurrentHashMap(segments, initialCapacityPerSegment);
    }

    @Bean
    public TTLManager ttlManager(ConcurrentHashMap store) {
        return new TTLManager(store, ttlIntervalMs);
    }

    @Bean
    public RDBManager rdbManager(ConcurrentHashMap store, ObjectMapper objectMapper) {
        return new RDBManager(store, dataPath(), objectMapper, rdbIntervalSeconds);
    }

    @Bean
    @DependsOn("rdbManager")
    public AOFManager aofManager(ConcurrentHashMap store, ObjectMapper objectMapper) {
        return new AOFManager(store, dataPath(), objectMapper);
    }
}
