# Mini DBMS 작업 지침

- 사용자 요청 범위는 DBMS-01 → DBMS-02 → DBMS-03이다. [요구사항](docs/requirements.md), [아키텍처](docs/architecture.md), [팀 규칙](TEAM_DEVELOPMENT_RULES.md)을 따른다.
- Java25, 독립 mini-dbms Maven Wrapper를 사용한다. SQL 파서와 메모리 B+ 트리는 직접 구현한다. JSON/HTTP/테스트는 기존 설계의 Jackson/JDK HttpServer/JUnit을 사용한다.
- 최초 기준은 사용자가 생성한 origin/ym-mini-dbms c5381b3이다. Jira 에픽 KJF-58 아래 요구사항별 Task·브랜치를 하나씩 연결한다. 새 브랜치는 구현 전 즉시 publish하고 upstream/원격 SHA를 검증한다.
- 이번 일괄 요청은 각 단계 구현·검증 후 커밋·Push·PR까지 포함한다. 첫 PR은 ym-mini-dbms, 이후 PR은 직전 검증 단계 브랜치를 대상으로 한다. 자동 승인·병합·Jira 완료 전환은 하지 않는다.
- Redis 작업공간과 소스는 수정하지 않는다. 현재 DBMS는 별도 worktree에서 구현한다. main·다른 팀원 브랜치는 변경하지 않는다. Force Push/Hard Reset/무관한 변경 폐기는 금지한다.
- mini-dbms/에서 ./mvnw test로 검증한다. CLI는 실제 다른 JVM, HTTP는 실제 클라이언트로 확인한다. 100만건 인덱스 성능은 실제 측정하고 p50/p95/처리량·조건·원본값을 기록한다.
- 필수 계약과 설계 선택, 저장 형식·지원 SQL·동시성·장애 한계를 한국어 문서/테스트에 남긴다. 신규 범위나 파괴적 작업만 확인하고, 이미 요청된 안전한 작업은 끝까지 진행한다.
