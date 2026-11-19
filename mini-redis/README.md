# Mini Redis

직접 구현한 해시 테이블, TTL, HTTP API, 상품 캐시 예제와 AOF를 제공하는 독립 Maven 프로젝트다. 아래 `cd mini-redis`는 저장소 루트 기준이며, 이미 이 폴더라면 생략한다.

## 문서 안내

| 문서 | 내용 |
|---|---|
| [요구사항](../docs/mini-redis/requirements.md) | 구현 순서, 필수 동작, API 계약, 단계별 완료 기준 |
| [아키텍처](../docs/mini-redis/architecture.md) | 모듈 책임, 의존 방향, 목표 폴더 구조, 기술 스택 |
| [작업 지침](../AGENTS.md) | 에이전트의 구현 순서와 검증·보고 규칙 |
| [팀 개발 규칙](../TEAM_DEVELOPMENT_RULES.md) | 요구사항·이슈·브랜치 연결, Git·Jira, PR 리뷰와 선별 통합 절차 |
| [0단계 검증 기록](../docs/mini-redis/redis-00-verification.md) | KJF-51 설정 범위, Java·Maven·OMX 검증 결과와 남은 제약 |
| [REDIS-01 검증 기록](../docs/mini-redis/redis-01-verification.md) | 해시 테이블·저장소 API, 사전 할당·리사이즈 설계, 단위 테스트 결과 |
| [REDIS-02 검증 기록](../docs/mini-redis/redis-02-verification.md) | HTTP API·오류 응답·TTL 미지원 정책, 서버 실행과 실제 네트워크 검증 |
| [REDIS-03 검증 기록](../docs/mini-redis/redis-03-verification.md) | TTL 경계·공통 잠금·다중 HTTP 요청 검증 |
| [REDIS-04 검증 기록](../docs/mini-redis/redis-04-verification.md) | 상품 API 캐시 적중·만료·무효화·장애 fallback |
| [REDIS-05 성능 비교](../docs/mini-redis/redis-05-verification.md) | 동일 조건 미사용·미스·적중의 실측값과 재현 방법 |
| [REDIS-AOF 복구 검증](../docs/mini-redis/redis-aof-verification.md) | 프레임·CRC·동기 기록·강제 종료 복구와 실제 비용 |

### 기능별 바로가기

- **Mini Redis**: [구현 단계](../docs/mini-redis/requirements.md#현재-범위와-구현-순서) · [필수 동작](../docs/mini-redis/requirements.md#mini-redis-필수-동작) · [HTTP API](../docs/mini-redis/requirements.md#외부-http-api)
- **검증과 복구**: [검증·비교 기준](../docs/mini-redis/requirements.md#검증과-비교-기준) · [선택적 AOF 설계](../docs/mini-redis/requirements.md#장애-복구-설계)
- **Mini DBMS**: [독립 프로젝트 안내](../mini-dbms/README.md). 현재 상품 API는 DBMS가 아니라 테스트용 원본을 사용한다.

## 개발 환경과 검증

설계 기준은 Java 25 LTS, Maven Wrapper, JDK `HttpServer`, Jackson, JUnit이다.

Java 25 JDK를 선택한 터미널에서 다음 명령으로 Maven과 프로젝트 설정을 확인한다. 첫 실행 시 Maven Wrapper가 지정된 Maven 배포판과 빌드 의존성을 내려받는다.

```sh
cd mini-redis
./mvnw --version
./mvnw test
```

`./mvnw test`는 저장소·TTL·동시성·상품 캐시·AOF와 실제 loopback HTTP/별도 JVM 테스트를 실행한다. 현재 누적 검증은135개 통과다. Java 25와 로컬 포트 바인딩 권한이 필요하다.

### HTTP 서버 실행

Java 25를 가리키는 `JAVA_HOME`이 설정된 셸에서 실행한다. 아래는 macOS/Linux 기준이다.

```sh
cd mini-redis
./mvnw compile org.apache.maven.plugins:maven-dependency-plugin:3.11.0:copy-dependencies -DincludeScope=runtime
"$JAVA_HOME/bin/java" -cp "target/classes:target/dependency/*" miniredis.Main 8080 100000
```

첫 인자는 포트(0이면 자동 할당), 두 번째는 예상 최대 고유 키 수다. 서버는 `127.0.0.1`에서 실행되며 Ctrl+C로 종료한다. 별도 터미널에서 확인한다.

```sh
curl -i -X PUT http://127.0.0.1:8080/keys/product:42 -H 'Content-Type: application/json' -d '{"value":"상품 응답"}'
curl -i http://127.0.0.1:8080/keys/product:42
curl -i -X DELETE http://127.0.0.1:8080/keys/product:42
curl -i http://127.0.0.1:8080/keys/product:42
```

기대 상태 코드는 **204 → 200 → 204 → 404**다. `ttlMs`는 선택적인 양의 정수이며, 생략한 덮어쓰기는 이전 만료를 제거한다. 잘못된 TTL은 `400 INVALID_TTL`이다.

### 대시보드와 능동 만료

Redis를 시작한 뒤 브라우저에서 `http://127.0.0.1:8080/dashboard`를 연다. 키 CRUD는 기존 `/keys/{key}`를 사용한다. `GET /stats`는 유효 키 수, 사용 중인 버킷 수, 최대 체인 길이, 리사이즈 횟수, 능동 만료 누적 횟수를 반환한다. 화면은 3초마다 통계를 갱신하고 유효 키 최대 80개, 사용 중인 버킷 최대 32개를 표시한다.

HTTP 서버의 만료 작업은 1초 간격으로 저장소를 살펴본다. YJ의 주기적 능동 만료 방식과 버킷 대시보드에서 가져온 기능이다. 조회 시에도 TTL을 확인하고, 능동 만료에서 지운 키는 절대 만료 시각으로 복구할 수 있으므로 AOF에 별도 삭제를 기록하지 않는다. 정리와 통계 계산은 각각 공통 저장소 락을 잡고 전체 버킷을 순회한다. 큰 저장소에서 스캔 중 요청이 기다릴 수 있다. 재현과 출처는 [KJF-64 검증 기록](../docs/mini-redis/kjf-64-verification.md)을 참고한다.

## 상품 API

[별도 JVM 실행과 HTTP 검증](../examples/product-api/README.md)을 따른다. 테스트 원본을 사용하는 예제로 Mini DBMS나 운영 서비스는 아니다.

### AOF 사용

위 빌드 후 `mini-redis/`에서 기존2개 인자 뒤 로컬 AOF 경로를 추가한다. 경로를 생략하면 기존 메모리 전용 모드다.

```sh
mkdir -p data
"$JAVA_HOME/bin/java" -cp 'target/classes:target/dependency/*' miniredis.Main 8080 100000 data/store.aof
```

같은 경로로 재시작하면 기록한 값·삭제·절대 만료를 복구한다. 로그는 삭제하지 말고 보존해야 한다. 기록/동기화 실패는503, 이후 쓰기는 재시작 검증 전까지 중단한다. 완전한 손상이면 시작을 거절한다. [파일 형식·복구 절차·보장 경계·비용](../docs/mini-redis/redis-aof-verification.md)을 확인한다. 자동 PR 병합이나 main 최종 통합은 수행하지 않는다.
