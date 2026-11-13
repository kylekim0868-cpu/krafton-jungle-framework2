# krafton-jungle-framework2

Java로 Mini Redis와 Mini DBMS를 직접 구현하고, 공통 기준으로 검증·비교하는 프로젝트다.

## 현재 상태

0단계 설정은 `ym-mini-redis` / [KJF-51](https://kylekim0868.atlassian.net/browse/KJF-51)에 보존한다. REDIS-01은 설정 커밋 `881be2c`에서 분기한 `feat/KJF-52-ym-hash-table` / [KJF-52](https://kylekim0868.atlassian.net/browse/KJF-52)에서 진행한다. 현재 구현 범위는 버킷 배열·체이닝 기반 메모리 저장소와 JUnit 단위 테스트다.

Mini Redis를 먼저 구현한다. 해시 테이블 → HTTP API → TTL과 동시성 → 상품 API 연동 → 성능 비교 순서로 진행하며, AOF는 선택 기능이다. Mini DBMS는 후속 작업이다.

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

REDIS-01의 `./mvnw test`는 저장소와 해시 테이블의 실제 JUnit 테스트를 실행한다. 0단계의 테스트 없는 빌드 설정 확인과 구별한다. HTTP 기능 테스트와 성능 실험은 아직 범위 밖이며, 해당 기능 구현 시 실행 방법·결과를 기록한다.
