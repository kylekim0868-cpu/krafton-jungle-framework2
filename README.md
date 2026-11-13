# krafton-jungle-framework2

Java로 Mini Redis와 Mini DBMS를 직접 구현하고, 공통 기준으로 검증·비교하는 프로젝트다.

## 현재 상태

REDIS-00~02 구현은 개인 통합 브랜치에 전달됐으며, 현재 REDIS-03~REDIS-AOF를 요구사항별 이슈·브랜치로 순차 구현한다. Mini DBMS는 후속 작업이다. 현재 기능과 검증은 [요구사항 연결표](docs/requirements.md#이슈브랜치-연결) 및 단계별 기록을 따른다.

작업은 **팀원별 요구사항 1개 → Jira 이슈 1개 → 작업 브랜치 1개 생성 → 즉시 원격 publish·upstream 연결 → 구현·검증** 순서로 진행한다. 브랜치 이름은 `<유형>/<이슈키>-<팀원 이니셜>-<작업명>`을 따른다. 최초 publish는 새 작업 커밋 없이 분기 기준 커밋을 올리는 절차이며, 이후 구현 커밋과 Push는 별도 승인 대상이다. 기존 `ym-mini-redis`는 설정 요구사항 `REDIS-00` / `KJF-51`에만 사용하며, 이후 요구사항에는 새 전용 브랜치를 만든다. [운영 규칙](TEAM_DEVELOPMENT_RULES.md)과 [요구사항별 연결표](docs/requirements.md#이슈브랜치-연결)를 확인한다.

## 문서 안내

| 문서 | 내용 |
|---|---|
| [요구사항](docs/requirements.md) | 구현 순서, 필수 동작, API 계약, 단계별 완료 기준 |
| [아키텍처](docs/architecture.md) | 모듈 책임, 의존 방향, 목표 폴더 구조, 기술 스택 |
| [작업 지침](AGENTS.md) | 에이전트의 구현 순서와 검증·보고 규칙 |
| [팀 개발 규칙](TEAM_DEVELOPMENT_RULES.md) | 요구사항·이슈·브랜치 연결, Git·Jira, PR 리뷰와 선별 통합 절차 |
| [0단계 검증 기록](docs/redis-00-verification.md) | KJF-51 설정 범위, Java·Maven·OMX 검증 결과와 남은 제약 |
| [REDIS-01 검증 기록](docs/redis-01-verification.md) | 해시 테이블·저장소 API, 사전 할당·리사이즈 설계, 단위 테스트 결과 |
| [REDIS-02 검증 기록](docs/redis-02-verification.md) | HTTP API·오류 응답·TTL 미지원 정책, 서버 실행과 실제 네트워크 검증 |

| [REDIS-03 검증 기록](docs/redis-03-verification.md) | TTL 경계·공통 잠금·다중 HTTP 요청 검증 |

| [REDIS-04 검증 기록](docs/redis-04-verification.md) | 상품 API 캐시 적중·만료·무효화·장애 fallback |

| [REDIS-05 성능 비교](docs/redis-05-verification.md) | 동일 조건 미사용·미스·적중의 실측값과 재현 방법 |

### 기능별 바로가기

- **Mini Redis**: [구현 단계](docs/requirements.md#현재-범위와-구현-순서) · [필수 동작](docs/requirements.md#mini-redis-필수-동작) · [HTTP API](docs/requirements.md#외부-http-api)
- **검증과 복구**: [검증·비교 기준](docs/requirements.md#검증과-비교-기준) · [선택적 AOF 설계](docs/requirements.md#장애-복구-설계)
- **Mini DBMS**: [후속 요구사항](docs/requirements.md#mini-dbms-후속-요구사항)

## 개발 환경과 검증

설계 기준은 Java 25 LTS, Maven Wrapper, JDK `HttpServer`, Jackson, JUnit이다.

Java 25 JDK를 선택한 터미널에서 다음 명령으로 Maven과 프로젝트 설정을 확인한다. 첫 실행 시 Maven Wrapper가 지정된 Maven 배포판과 빌드 의존성을 내려받는다.

```sh
cd mini-redis
./mvnw --version
./mvnw test
```

`./mvnw test`는 저장소·TTL·동시성과 실제 loopback HTTP 테스트를 실행한다. Java 25와 로컬 포트 바인딩 권한이 필요하다.

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

### 상품 API

[별도 JVM 실행과 HTTP 검증](examples/product-api/README.md)을 따른다. 테스트 원본을 사용하는 예제로 Mini DBMS나 운영 서비스는 아니다.
