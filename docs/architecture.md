# Mini Redis·Mini DBMS 아키텍처

Mini Redis는 직접 만든 해시 테이블을 HTTP로 제공한다. 상품 API는 조회 결과를 캐싱하고, Mini DBMS는 후속 단계에서 상품 데이터의 원본을 맡는다. Mini Redis와 테스트용 원본으로 상품 API의 캐시 동작을 검증한다. [단계별 완료 기준](requirements.md#현재-범위와-구현-순서)

## 모듈 구조와 의존 방향

```text
외부 클라이언트
  └─ HTTP → 상품 API
              ├─ HTTP → Mini Redis → 저장소 → 해시 테이블
              └─ 원본 조회 → 테스트용 데이터 → 후속 단계에서 Mini DBMS HTTP API로 전환

Mini DBMS 후속 구성
  CLI 또는 HTTP API → 같은 SQL 엔진 → 테이블 파일
                                    └─ B+ 트리에서 행의 파일 위치 조회
```

| 모듈 | 책임 |
|---|---|
| Mini Redis HTTP | JSON 요청과 상태 코드를 처리하고 저장소를 호출한다. 외부 요청 형식은 [요구사항](requirements.md#외부-http-api)을 따른다. |
| Mini Redis 저장소 | 저장·조회·삭제 명령을 처리한다. TTL 단계에서 만료 판정과 잠금을 이 경계에 둔다. HTTP 서버의 요청 처리 스레드는 같은 저장소 인스턴스를 사용한다. |
| 해시 테이블 | 버킷 배열, 키 비교, 충돌 처리, 리사이즈를 담당한다. 저장소와 별도로 단위 테스트할 수 있게 구성한다. |
| 상품 API | 캐시를 먼저 조회한다. 미스이면 원본에서 가져온 응답을 캐시에 저장하고 반환한다. 캐시 서버가 중단되면 원본을 조회한다. |
| Mini DBMS | 원본 레코드를 테이블 파일에 보관한다. 후속 단계에서 파일 기반 SQL 엔진, 메모리 B+ 트리, 스레드 풀 기반 HTTP API 순으로 구현한다. |

`MiniRedisHttpServer`는 JDK HttpServer와 고정 4개 작업 스레드를 소유하고 `KeysHandler`에 같은 `KeyValueStore`를 주입한다. HTTP 경계는 JSON·상태 코드만 처리한다. 저장소의 synchronized 메서드가 직접 만든 해시 테이블 엔트리의 값·절대 만료 시각을 함께 보호한다. 조회·만료 판정·삭제는 한 임계영역이다. `Clock`을 주입해 정확한 만료 경계를 테스트하며, TTL 없는 덮어쓰기는 만료를 제거한다. `size()`는 만료 항목을 순회 정리한 유효 키 수다. 백그라운드 만료 스캐너는 없으며 재접근하지 않는 만료 항목은 get/size 때 정리된다. 서버 생성·바인딩은 start 때, 종료 시 서버와 Executor를 정리한다. 고정 스레드 풀의 대기열은 제한하지 않아 운영용 부하 차단은 범위 밖이다.

상품 예제는 같은 Maven 빌드의 `miniredis.product` 패키지이며 [실행 안내](../examples/product-api/README.md)를 둔다. ProductMain과 Mini Redis Main은 별도 JVM이다. ProductOrigin 경계 뒤의 테스트 원본을 향후 DBMS로 교체할 수 있다. HttpKeyCache는 재사용 HttpClient, 전체 응답 deadline, 응답 크기 제한을 적용한다. 캐시 장애는 관찰 가능한 카운터를 늘리고 원본으로 fallback하며 원본 실패는503이다.

채택한 AOF는 저장소의 쓰기 경계에 연결한다. MutationLog.append가 프레임 전체 쓰기와 force(true)를 마친 뒤 저장소가 메모리를 변경한다. 동일 저장소 모니터가 로그 순서와 메모리 순서를 함께 보호한다. 기록·동기화 실패는503과 재시작까지 쓰기 차단이며 읽기는 직전 메모리 상태를 제공한다. 실패 응답의 기록도 재시작 후 복구될 수 있어 결과는 불확정이다.

Main의 선택적인 세 번째 인자로 AOF 경로를 받는다. openPersistent가 HTTP 시작 전에 전체 로그를 순서 재생하고 마지막 상태에서 만료를 정리한다. 불완전한 마지막 프레임만 절단·동기화하고 완전한 손상은 시작을 중단한다. AofLog는 JVM registry에서 실제 파일 중복을 채널 개방 전에 거절하고, 별도 프로세스에는 FileLock을 사용한다. 메모리 모드도 유지하며 저장소/로그 자원의 소유자는 Main이다. [상세 형식과 장애 절차](redis-aof-verification.md).

## 폴더 구조

현재 Mini Redis 구조와 후속 DBMS의 예정 위치다. DBMS 디렉터리와 구현은 아직 생성하지 않았다.

```text
저장소 루트
├── AGENTS.md                    Codex 작업 순서와 검증 지침
├── TEAM_DEVELOPMENT_RULES.md     개인 작업과 팀 통합 규칙
├── README.md                    프로젝트 소개와 문서 안내
├── docs/
│   ├── requirements.md          요구사항과 단계별 완료 기준
│   ├── architecture.md          모듈 구조와 데이터 흐름
│   ├── redis-00-verification.md  0단계 설정 검증 결과와 제약
│   ├── redis-01-verification.md  저장소 설계와 단위 테스트 결과
│   ├── redis-02-verification.md  HTTP API 이력
│   ├── redis-03-verification.md  TTL·공통 잠금
│   ├── redis-04-verification.md  상품 API 캐시
│   ├── redis-05-verification.md  캐시 성능 비교
│   ├── redis-aof-verification.md 프레임·복구·동기화 비용
│   └── benchmarks/              실제 측정 JSON Lines
├── mini-redis/
│   ├── pom.xml                  0단계 Maven 설정
│   ├── mvnw, mvnw.cmd           Maven Wrapper
│   ├── .mvn/wrapper/            Maven Wrapper 설정
│   └── src/
│       ├── main/java/miniredis/ store·http·product·persistence·benchmark
│       └── test/java/miniredis/ 단위·실제 HTTP·별도 JVM 복구 테스트
├── examples/
│   └── product-api/            4단계 외부 캐시 사용 예제
└── mini-dbms/                   후속 예정 (아직 미생성)
```

`src/main/java/` 아래에서 해시 테이블과 저장소는 `store` 패키지에, HTTP 서버와 요청 처리는 `http` 패키지에 둔다. 테스트 패키지는 검증할 코드의 패키지와 맞춘다. AOF 코드는 `persistence`, 상품 예제는 `product`, 재현 실험은 `benchmark` 패키지에 둔다. Mini DBMS의 Maven 설정과 소스 구조는 해당 프로젝트에 착수할 때 추가한다.

## 기술 스택과 실행

- Java 25 LTS와 Maven Wrapper를 사용한다.
- HTTP 서버는 JDK `HttpServer`, JSON 처리는 Jackson 3.3.0, 테스트는 JUnit을 사용한다. `Main`은 포트·예상 키 수와 선택 AOF 경로를 받아 loopback 주소에서 서버를 실행한다.
- Maven Wrapper를 구성한 뒤 `mini-redis/`에서 `./mvnw test`를 실행한다. HTTP 기능 테스트는 서버를 기동해 별도 HTTP 클라이언트가 실제 요청과 응답을 확인한다.
- 상품 API 연동 시 Mini Redis와 상품 API는 독립 프로세스로 실행한다. Mini DBMS 연동 단계에서는 DBMS도 별도 프로세스로 실행한다.
