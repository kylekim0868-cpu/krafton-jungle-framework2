# Mini Redis · Mini DBMS

하나의 저장소에서 두 Java 프로젝트를 **독립적으로 빌드·실행·검증**한다. 공통 부모 POM이나 모듈 간 소스 의존성은 없다.

| 프로젝트 | 기능 | 실행·테스트 | 요구사항 | 아키텍처 |
|---|---|---|---|---|
| Mini Redis | 해시 테이블, TTL, HTTP, 상품 캐시 예제, AOF | [안내](mini-redis/README.md) | [REDIS-00~AOF](docs/mini-redis/requirements.md) | [설계](docs/mini-redis/architecture.md) |
| Mini DBMS | SQL·JSONL 파일, B+ 트리, CLI, HTTP | [안내](mini-dbms/README.md) | [DBMS-01~03](docs/mini-dbms/requirements.md) | [설계](docs/mini-dbms/architecture.md) |

## 구조

```text
krafton-jungle-framework2/
├── mini-redis/             # 자체 pom.xml·Maven Wrapper·소스·테스트
├── mini-dbms/              # 자체 pom.xml·Maven Wrapper·소스·테스트
├── docs/
│   ├── mini-redis/         # Redis 요구사항·설계·검증·벤치마크
│   └── mini-dbms/          # DBMS 요구사항·설계·검증·벤치마크
├── examples/product-api/  # Redis의 테스트용 원본 기반 캐시 예제
├── AGENTS.md               # 공통 작업·검증 지침
└── TEAM_DEVELOPMENT_RULES.md
```

## 독립 테스트

Java **25** JDK를 `JAVA_HOME`으로 선택한다. 첫 실행에는 Maven과 의존성 다운로드가 필요하다. 테스트는 loopback 포트 바인딩과 별도 JVM 실행을 사용하며, 미리 실행한 서버가 필요하지 않다.

저장소 루트에서 각각 실행한다.

```sh
(cd mini-redis && ./mvnw test)
(cd mini-dbms && ./mvnw test)
```

두 결과를 한 번에 확인하려면:

```sh
(cd mini-redis && ./mvnw test) && (cd mini-dbms && ./mvnw test)
```

각 프로젝트는 자체 `target/`과 임시 테스트 데이터를 사용한다. 실제 실행 데이터는 각각 `mini-redis/data/`, `mini-dbms/data/`에 두며 Git에 포함하지 않는다. 실행 예시 포트는 Redis **8080**, 상품 API **8081**, DBMS **8090**으로 구분한다.

## 독립성의 범위

- Redis 테스트에는 DBMS 서버가 필요 없고, DBMS 테스트에는 Redis 서버가 필요 없다.
- 현재 상품 API 원본은 테스트용 구현이다. **DBMS 원본 + Redis 캐시의 실제 연동·통합 테스트는 아직 구현하지 않았다.**
- 개인 통합 대상은 `ym-mini-dbms`다. [폴더·브랜치 통합 검증 기록](docs/workspace-verification.md)을 확인한다. `main` 통합과 실제 Redis–DBMS 연동은 별도 범위다.
- 기능별 문서는 각 `docs/mini-*/`에서 관리하고, 루트 문서는 탐색과 공통 정책만 담당한다.

[전체 요구사항 안내](docs/requirements.md) · [전체 구조](docs/architecture.md) · [작업 지침](AGENTS.md) · [팀 개발 규칙](TEAM_DEVELOPMENT_RULES.md)
