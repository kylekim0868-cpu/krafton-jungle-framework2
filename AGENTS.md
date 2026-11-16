# Mini Redis · Mini DBMS 공통 작업 지침

- 구현 전 해당 모듈의 요구사항·아키텍처·코드·테스트를 읽는다. Redis는 [요구사항](docs/mini-redis/requirements.md)·[아키텍처](docs/mini-redis/architecture.md), DBMS는 [요구사항](docs/mini-dbms/requirements.md)·[아키텍처](docs/mini-dbms/architecture.md)를 따른다.
- 두 프로젝트는 같은 저장소의 독립 Maven 프로젝트다. Java 25를 사용하며 Redis 해시 테이블, DBMS SQL 처리기·B+ 트리는 직접 구현한다. HTTP/JSON/빌드/테스트에는 각 설계의 도구를 사용한다.
- 기능·설계·실행·검증 문서는 해당 모듈의 `docs/mini-redis/` 또는 `docs/mini-dbms/`에만 관리한다. 루트 문서에는 공통 정책과 탐색 링크만 두며 API 계약을 중복 작성하지 않는다.
- 현재 요청은 두 구현의 동일 작업 폴더 배치, 문서 분리, 독립 테스트 검증 결과를 `ym-mini-dbms`에 반영하는 것이다. 사용자가 이번 통합의 커밋·Push·대상 PR 병합을 요청했다. 실제 Redis–DBMS 연동과 통합 테스트는 제외한다. 기존 사용자의 Redis 미커밋 변경 4개는 통합 커밋에서 제외하고 보존한다. main·다른 팀원 브랜치·Jira 상태는 변경하지 않는다.
- 각 모듈에서 `./mvnw test`로 검증한다. 루트에서는 `(cd mini-redis && ./mvnw test)`와 `(cd mini-dbms && ./mvnw test)`를 각각 실행한다. 포트·데이터·target을 모듈별로 분리하며 상대 모듈의 서버 실행을 요구하지 않는다.
- 필수 동작과 개인 설계 선택을 구분하고, API·동시성·저장 형식 변경의 이유와 영향을 해당 모듈 문서와 테스트에 남긴다. 통과·실패·미실행을 구분하며 성능 수치는 실제 측정값만 사용한다.
- [팀 개발 규칙](TEAM_DEVELOPMENT_RULES.md)에 따라 새 구현은 팀원별 요구사항 ID 하나에 실제 Jira 이슈 하나·전용 브랜치 하나를 연결한다. 기존 연결을 먼저 확인하고, 승인된 새 작업 브랜치는 구현·새 커밋 전 즉시 publish하여 upstream·원격 SHA를 확인한다. 문서·계획 요청만으로 이슈·브랜치를 생성하지 않는다.
- `ym-mini-redis`는 Redis 구현만 유지하고 이번 통합 결과를 역으로 병합하지 않는다. `ym-mini-dbms`에는 mini-redis와 mini-dbms 두 독립 모듈을 포함한다.
- main과 다른 팀원의 브랜치를 변경하지 않고 Force Push·Hard Reset·무관한 변경 폐기를 하지 않는다. 커밋·Push·PR·병합은 사용자의 명시적 요청 범위와 팀 규칙을 따른다.
- 답변과 주요 설계 설명은 한국어로 작성한다. 기본적으로 한 세션에서 구현·검증하며 불필요한 병렬 에이전트 작업은 시작하지 않는다.
