# Mini Redis

Java 17 + Spring Boot 3 로 구현한 인메모리 Key-Value 저장소.
`java.util.HashMap` 없이 해시 테이블을 직접 구현하고, TTL·동시성·영속성(AOF/RDB)·REST API·시각화 대시보드를 제공한다.

## 실행

```bash
./gradlew bootRun      # http://localhost:8080 에서 대시보드 확인
./gradlew test         # 단위 + 통합 테스트
```

## 구조

| 패키지 | 클래스 | 역할 |
|---|---|---|
| `core` | `HashTable` | 버킷 배열 + Separate Chaining, 적재율 0.75 초과 시 2배 리사이징, TTL / Lazy Expiration |
| `core` | `ConcurrentHashMap` | 세그먼트별 `ReentrantReadWriteLock` 으로 `HashTable` 을 감싼 Thread-Safe 래퍼 |
| `core` | `TTLManager` | 주기적으로 만료 키를 제거하는 Active Expiration 스케줄러 |
| `persistence` | `AOFManager` | 쓰기 명령을 AOF 파일에 기록, 시작 시 Replay 로 복구 |
| `persistence` | `RDBManager` | 주기적 / 수동 JSON 스냅샷 저장 및 복구 |
| `config` | `AppConfig` | 빈 등록 및 시작 시 복구 순서(RDB → AOF → TTL 스케줄러) 보장 |
| `api` | `RedisController` | REST API |

## REST API

| Method | Path | 설명 |
|---|---|---|
| POST | `/api/redis` | 저장/수정 `{"key":"k1","value":"v1","ttl":60}` |
| GET | `/api/redis/{key}` | 단건 조회 (없거나 만료 시 404) |
| DELETE | `/api/redis/{key}` | 단건 삭제 |
| GET | `/api/redis` | 전체 목록 + 통계 + 버킷 분포 |
| DELETE | `/api/redis` | 전체 초기화 (FLUSH) |
| POST | `/api/redis/save` | RDB 스냅샷 수동 저장 |
| GET | `/api/slow-db?id=..` | 캐시 성능 비교 Mock (Cache-Aside, Miss 시 500ms 지연 후 30초 캐싱) |

## 설정 (`application.yml`)

| 키 | 기본값 | 설명 |
|---|---|---|
| `miniredis.data-dir` | `data` | AOF / RDB 파일 저장 경로 |
| `miniredis.segments` | `16` | 세그먼트 락 개수 |
| `miniredis.initial-capacity-per-segment` | `4` | 세그먼트별 초기 버킷 수 |
| `miniredis.ttl.active-interval-ms` | `1000` | Active Expiration 주기 |
| `miniredis.rdb.interval-seconds` | `30` | RDB 스냅샷 주기 |
