# REDIS-02 · 외부 HTTP API 검증

## 작업 연결과 범위

- 요구사항: [REDIS-02](requirements.md#외부-http-api)
- Jira: [KJF-53](https://kylekim0868.atlassian.net/browse/KJF-53), 상위 KJF-30, 담당 YM. 별도 마감일 미정.
- 브랜치: [`feat/KJF-53-ym-http-api`](https://github.com/kylekim0868-cpu/krafton-jungle-framework2/tree/feat/KJF-53-ym-http-api).
- 기준 커밋: `c4dabbbdbd53c8515d47e074a3608a04b2da8961` (REDIS-01 / KJF-52). 선행 구현이 main에 없어 구현 계획의 기준 커밋에서 분기했다.
- **구현 변경 전에 최초 publish 완료.** 당시 원격 SHA가 위 기준 커밋과 일치하고 upstream이 `origin/feat/KJF-53-ym-http-api`임을 확인했다. 최초 publish는 기준 커밋만 전달한 것이며, HTTP 구현은 아래와 같이 미커밋 작업 트리에서 검증한 뒤 별도 커밋·Push 요청으로 전달한다.
- 착수 전의 publish 절차 문서 4개 변경은 보존했다. 기존 저장소·해시 테이블·단위 테스트는 변경하지 않았다.

## 구조와 책임

```text
Main → MiniRedisHttpServer → KeysHandler → KeyValueStore → StringHashTable
          생명주기·직렬화       HTTP/JSON       기존 메모리 저장소
```

- `Main`은 포트와 예상 최대 키 수를 받아 loopback 주소에 서버를 띄운다.
- `MiniRedisHttpServer`는 저장소 하나를 핸들러에 주입하고 단일 작업 스레드에서 요청을 직렬 처리한다. 포트 바인딩·서버 생성은 start 시점에 수행하고 close는 멱등이다. 시작 전 close도 스레드나 포트를 남기지 않는다.
- `KeysHandler`는 경로·메서드·JSON 검증과 상태 코드를 담당한다. 모든 입력 검증을 마친 뒤 저장소를 변경한다. 오류 시 원래 값을 유지한다.
- 기존 `KeyValueStore`는 HTTP/JSON 의존성을 추가하지 않는다. 서버 실행 중 외부 코드에서 같은 저장소를 직접 변경하지 않는 단일 소유 규칙을 따른다.
- Jackson은 Maven Central의 release 메타데이터를 확인해 `tools.jackson.core:jackson-databind:3.3.0`으로 고정했다. 필요한 core·annotations는 전이 의존성으로 포함하며 기존 JUnit 버전은 유지한다.

## 현재 HTTP 계약

| 요청 | 결과 |
|---|---|
| PUT `/keys/{key}`, `{"value":"문자열"}` | 저장·덮어쓰기 후 204, 본문 없음 |
| GET, 키 존재 | 200, `{"value":"문자열"}`, `application/json; charset=utf-8` |
| GET, 키 없음 | 404, 본문 없음 |
| DELETE | 삭제 여부와 관계없이 204, 본문 없음 |
| PUT에 `ttlMs` 필드 포함 | 400, `{"error":"TTL_NOT_SUPPORTED"}`, 신규 저장·덮어쓰기 없음 |
| 잘못된 JSON·누락/null/비문자열 value·미지원 필드 | 400, `{"error":"INVALID_REQUEST"}` |
| 빈 키·슬래시 포함 키·제어 문자·잘못된 UTF-8·쿼리 | 400, `{"error":"INVALID_KEY"}` |
| 잘못된 경로 | 404 |
| 미지원 메서드 | 405, `Allow: PUT, GET, DELETE` |
| PUT Content-Type이 application/json이 아님/누락 | 415 |
| PUT 본문이 1 MiB 초과 | 413 |

- 빈 문자열 **값**은 200으로 조회한다. HTTP 경로에서는 빈 키를 거절하지만 기존 저장소의 빈 키 허용 계약은 바꾸지 않는다.
- URI의 키는 UTF-8로 정확히 한 번 디코딩한다. `+`는 공백으로 바꾸지 않는다. `%2F`는 슬래시여서 거절하지만 `%252F`는 문자 `%2F`로 보존한다.
- PUT은 JSON 객체 하나에 문자열 value 필드 하나만 허용한다. 중복 필드와 뒤에 추가된 JSON도 거절한다. Jackson으로 응답을 생성하고 UTF-8 바이트 수로 응답 길이를 계산한다.
- 요청/응답은 exchange 종료 시 닫는다. 오류 응답은 연결을 닫으며 스택 트레이스를 본문에 노출하지 않는다. 예상하지 못한 내부 RuntimeException은 서버 로그와 500으로 처리한다.

## TTL 처리 이유

TTL은 REDIS-03 범위이므로 현재 `ttlMs`는 양수·0·음수·실수·문자열·null 등 값과 관계없이 거절한다. 이를 무시하고 204를 반환하면 클라이언트가 만료가 적용됐다고 오해할 수 있다. 최종 TTL 계약은 요구사항에 보존하고, REDIS-03에서 양의 정수 TTL 지원·검증·만료 처리와 관련 테스트를 추가한다. 이번에는 시계·타이머·저장소 잠금을 만들지 않는다.

## 자동 검증

Java 25 JDK를 선택한 환경에서 저장소 루트 기준:

```sh
cd mini-redis
./mvnw test
```

2026-11-13에 기준 커밋 `c4dabbb` 위 **미커밋 작업 트리**에서 Temurin 25.0.5 / Maven 3.9.11로 검증했다. 시스템 기본 JDK는 17이어서 기존 임시 JDK 25와 Maven 캐시를 환경 변수로 선택했다.

| 검사 | 결과 |
|---|---|
| 기존 저장소·해시 테이블 JUnit | 33개 실행, 실패·오류·생략 0 |
| 실제 JDK HttpClient → HttpServer 테스트 | 50개 실행, 실패·오류·생략 0 |
| 전체 | **83개 실행, 실패·오류·생략 0, BUILD SUCCESS** |
| 독립 JVM `Main` + 별도 curl 프로세스 | **204 → 200 → 204 → 404**, 한글 값 보존 |
| 독립 서버에 ttlMs PUT 후 GET | 400 TTL_NOT_SUPPORTED, GET 404 |
| SIGTERM 종료·shutdown hook | JVM 종료 후 같은 포트 재바인딩 성공 |
| 운영 소스 컴파일러 검사 | Java 25 `--release 25 -Xlint:all -Werror` 통과 |

HTTP 테스트는 매번 새 저장소와 `127.0.0.1:0` 서버를 만들고 실제 TCP로 요청한다. 핸들러 모킹이나 직접 호출로 대체하지 않는다. 타임아웃과 종료 정리를 포함한다. 기본 CRUD·덮어쓰기·빈 값·반복 삭제·JSON 오류와 상태 보존·TTL 거절·한글/이스케이프·키 인코딩·라우팅·본문 제한·다중 연결의 직렬 처리·생명주기를 검증한다.

테스트를 먼저 작성했을 때는 HTTP 서버 클래스가 없어 컴파일 실패했다. 이후 수명주기 테스트에서 start 이전에 생성·바인딩한 JDK 서버를 stop해도 포트가 남는 문제를 재현했고, 서버 생성 자체를 start로 옮겨 회귀 테스트를 통과시켰다.

## 독립 서버 실행·외부 호출

[README 실행 절차](../README.md#http-서버-실행)를 따른다. 런타임 의존성 준비에는 Maven Dependency Plugin의 고정 버전 goal을 사용한다. 이는 실행용 파일을 target에 복사하는 빌드 도구이며 추가 애플리케이션 의존성을 도입하지 않는다.

```sh
cd mini-redis
./mvnw compile org.apache.maven.plugins:maven-dependency-plugin:3.11.0:copy-dependencies -DincludeScope=runtime
"$JAVA_HOME/bin/java" -cp "target/classes:target/dependency/*" miniredis.Main 8080 100000
```

macOS/Linux 기준이며 Windows에서는 classpath 구분자 등을 조정해야 한다. 실제 Windows 실행은 검증하지 않았다. Ctrl+C로 종료할 수 있고 포트 0을 주면 실제 선택한 포트를 출력한다.

## 제약과 미실행 항목

- 샌드박스 안에서는 DNS와 포트 바인딩이 차단되어 처음 검사에 실패했다. 필요한 권한으로 Maven 다운로드·실제 소켓 테스트·독립 서버 검증을 재실행했으며 테스트를 생략하거나 성공으로 위장하지 않았다.
- Maven의 Guice Unsafe 경고는 기존 0단계와 같고 빌드 실패 원인이 아니다.
- 인증·TLS·외부 배포용 접근 제어·느린 클라이언트 대응·요청 큐 제한은 아직 없다. 단일 스레드 직렬 처리이므로 운영용 처리량이나 다중 처리 안전성을 주장하지 않는다. Main의 기본 바인딩은 loopback이다.
- HTTP 본문 오류 시 상태 보존은 검증했지만 응답 전송이 끊기면 저장은 끝났어도 클라이언트가 응답을 못 받을 수 있다. 분산 트랜잭션이나 응답 전달의 원자성은 보장하지 않는다.
- 성능 실험, TTL·동시성 잠금, AOF·장애 복구는 미실행이며 후속 단계다. 별도 lint 도구는 추가하지 않고 JDK 경고 검사와 문서/차이 검사로 확인한다.
- 커밋·Push·PR 작성은 사용자의 별도 요청을 받아 진행하며 최초 publish와 구별한다. 선행 PR #21이 병합된 `ym-mini-redis`를 이번 작업 PR의 대상으로 사용한다. PR 병합과 Jira 완료 전환은 별도 권한·통합 조건을 따른다.
