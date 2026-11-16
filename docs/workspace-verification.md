# 동일 작업 폴더·독립 모듈 검증

## 최초 로컬 배치의 범위와 출처

- 최초 폴더 배치 검증일: 2026-11-16, 아래 첫 검증은 로컬 미커밋 변경 상태의 기록이다.
- 작업 폴더: `krafton-jungle-framework2/`, 현재 브랜치 `ym-mini-redis`, 기준 HEAD `cf0b5d6530659a2c2879d1bcde75f34de82f51c9`.
- Redis: 현재 작업 폴더를 유지했다. 시작 시 사용자가 수정한 Main.java, MiniRedisHttpServer.java, AofLog.java, StringHashTable.java의 내용을 SHA-256으로 비교해 보존을 확인했다.
- DBMS: `feat/KJF-61-ym-http-concurrency`의 `322acccb7b8ebe8b9550d7d97f3fcac08829bd59`에서 추적된 `mini-dbms/` 구현·빌드·테스트 파일 28개를 가져왔다. 새 모듈 README를 제외한 28개 파일은 출처와 바이트 단위로 일치한다. 기존 별도 DBMS worktree는 수정·삭제하지 않았다.
- 이는 **로컬 파일 배치 통합**이며 Git merge commit이나 원격 PR 병합이 아니다. 새 브랜치·커밋·Push·Jira 수정은 하지 않았다. 출처 PR #37~39의 상태도 이번 작업에서 변경하지 않았다.

## 문서·빌드 경계

| 항목 | Redis | DBMS |
|---|---|---|
| 실행 안내 | [모듈 README](../mini-redis/README.md) | [모듈 README](../mini-dbms/README.md) |
| 요구사항·설계·검증·벤치마크 | `docs/mini-redis/` | `docs/mini-dbms/` |
| 빌드 | 독립 pom.xml·Maven Wrapper·target | 독립 pom.xml·Maven Wrapper·target |
| 실행 데이터 예시 | `mini-redis/data/` | `mini-dbms/data/` |

기존 Redis 검증 이력과 벤치마크 원본을 이동하고 상대 링크를 갱신했다. DBMS 검증 이력과 실측 원본도 별도 문서 폴더에 배치했다. 루트 README·요구사항·아키텍처는 두 모듈의 안내, AGENTS·팀 규칙은 공통 정책을 담당한다. 모듈 간 Maven 의존성이나 루트 부모 POM은 추가하지 않았다. target과 런타임 data는 모듈별로 Git 제외했다.

## 최초 로컬 배치의 실제 테스트 결과

Java 25.0.5+8-LTS / Maven 3.9.11에서 저장소 루트 기준 아래 명령을 실행했다. 두 테스트 실행은 같은 작업 폴더에서 함께 진행됐고, 미리 실행한 Redis/DBMS 서버는 필요하지 않았다.

| 명령 | 결과 | 실패·오류·생략 | 소요 시간 |
|---|---|---|---|
| `(cd mini-redis && ./mvnw test)` | **135개, BUILD SUCCESS** | 모두 0 | 10.857초 |
| `(cd mini-dbms && ./mvnw test)` | **75개, BUILD SUCCESS** | 모두 0 | 7.005초 |

테스트에는 실제 loopback HTTP, 별도 JVM, 재시작·장애·동시성 검증이 포함된다. DBMS 장애 주입 테스트의 저장소 오류 로그는 의도한 실패 응답 검증이며 테스트 실패가 아니다. 실행 시간은 이번 로컬 테스트 소요 시간이지 성능 벤치마크 수치가 아니다.

환경 선택에는 해당 머신의 Java 25 JAVA_HOME과 기존 Maven 캐시를 사용했다. 재현할 때는 본인 환경의 Java 25를 선택한다. 로그는 로컬 `/private/tmp/workspace-mini-redis-test.log`, `/private/tmp/workspace-mini-dbms-test.log`에 기록했다.

추가 정적 검증도 통과했다: Markdown 23개 문서의 상대 링크·앵커 87개, 양쪽 Wrapper의 `sh -n`, `git diff --check`, 두 모듈의 target/data Git 제외 설정. Surefire XML 합계로 테스트 수와 실패·오류·생략 0을 재확인했다. Redis 캐시/AOF 및 DBMS 100만행 벤치마크 원본은 출처와 바이트 단위로 동일하다.

## 제외·미실행

- 실제 DBMS 원본 어댑터와 Redis–DBMS 연동/통합 테스트는 구현·실행하지 않았다. 상품 API는 기존 테스트용 원본을 사용한다.
- 소스 알고리즘·API·저장 형식을 변경하지 않았으므로 100만행 DBMS 실험 및 Redis 캐시/AOF 벤치마크를 새로 측정하지 않았다. 기존 실측 원본은 변경 없이 보존했다.
- 최초 로컬 배치 단계에서는 커밋·Push·PR 병합을 하지 않았다. 후속 사용자 요청으로 아래 브랜치 통합을 진행한다. main 통합과 Jira 완료 전환은 계속 제외한다.

## ym-mini-dbms 브랜치 통합

후속 사용자 요청에 따라 대상 브랜치를 `ym-mini-dbms`로 정했다. 통합 전용 브랜치는 `refactor/KJF-58-ym-independent-modules`이며 새 기능 Task·요구사항별 작업 브랜치를 대체하지 않는다.

- Redis 출처: `origin/ym-mini-redis`의 `cf0b5d6530659a2c2879d1bcde75f34de82f51c9`. 이 커밋까지의 기존 구현·주석은 포함하며 사용자 미커밋 수정 4개는 제외한다.
- DBMS 출처: `origin/feat/KJF-61-ym-http-concurrency`의 `322acccb7b8ebe8b9550d7d97f3fcac08829bd59`. DBMS-01~03의 누적 구현과 이력을 보존한다.
- 대상 시작점: `origin/ym-mini-dbms`의 `c5381b323311e6738fc0f48afefd397ba0db3e2d`.
- 최초 publish: Redis 기준 `cf0b5d6`에서 새 커밋 없이 원격에 publish했고 로컬·원격 SHA와 upstream을 확인했다. 이전 요청으로 이미 준비된 폴더 정리 변경을 이어받았으며 publish 뒤 통합 커밋을 만든다.
- 승인 범위: 통합 커밋·Push·`ym-mini-dbms` 대상 PR 병합. main 변경·Force Push·자동 승인·Jira 완료 전환은 제외한다.
- 출처 PR: DBMS #37~39, Redis 개인 통합 #32. 이번 통합은 별도 PR로 관리하며 출처 작업 브랜치는 삭제하지 않는다.

### 개인 통합 PR과 깨끗한 커밋 검증

- 통합 PR: [#40](https://github.com/kylekim0868-cpu/krafton-jungle-framework2/pull/40), 대상 `ym-mini-dbms`, head `refactor/KJF-58-ym-independent-modules`.
- 실제 DBMS 이력 연결 merge commit: `fa5f01cbb7efaceb01789eab333196a52a385424`. 결과 트리는 직전 폴더 정리 커밋과 같으며 root 공통 파일 7개의 충돌은 모듈별 단일 문서 구조로 해결했다.
- 작업 도중 원격 ym-mini-dbms가 `77574c12fe955789137a47947e3321744482db2d`(PR #37 병합)로 갱신됐다. 새 기준을 fetch하고 정상 merge로 보존했다. 출처 PR #37/#38/#39가 이미 외부에서 병합된 상태임도 확인했으며 이번 작업이 해당 PR을 병합한 것은 아니다.
- 위 fa5f01c의 **미커밋 변경 없는 검증 worktree**에서 다시 테스트했다. Redis **135개/실패·오류·생략0/11.345초**, DBMS **75개/실패·오류·생략0/7.018초**, 모두 BUILD SUCCESS. 기존 Redis 사용자 미커밋 주석을 포함하지 않은 결과다.
- 커밋된 Redis 구현·빌드·테스트는 cf0b5d6, DBMS 구현·빌드·테스트는 322accc와 동일하다. 각각 새 README만 추가했고 실제 코드·알고리즘·저장 형식은 바꾸지 않았다.
- 최종 사용자 지정: **ym-mini-dbms에는 두 모듈**, **ym-mini-redis에는 Redis 구현만** 둔다. ym-mini-redis와 main 원격 SHA는 이번 작업에서 변경하지 않는다.
- 사용자 미커밋 Redis 파일 4개는 스테이징·커밋하지 않고 기존 내용 그대로 보존한다. 통합 결과와 개인 변경의 상태를 구분해 보고한다.
