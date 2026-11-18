# REDIS-00 · 프로젝트 설정 검증

## 작업 범위

- 요구사항: [REDIS-00](requirements.md#현재-범위와-구현-순서)
- Jira: [KJF-51](https://kylekim0868.atlassian.net/browse/KJF-51)
- 작업 브랜치: `ym-mini-redis` (기존 설정 브랜치)
- 기준 커밋: `14eb217` (`Initial commit`)
- 검증일: 2026-11-13. 아래 결과는 커밋 전 작업 트리에서 확인했다.

Java 25용 Maven 프로젝트, Maven Wrapper, 소스·테스트 폴더, 요구사항·아키텍처·팀 개발 규칙, OMX의 공유 가능한 에이전트·프롬프트·스킬을 구성했다. `.codex/`의 인증·개인 설정·세션 데이터와 `.omx/` 런타임 상태, Maven 빌드 결과는 Git에 포함하지 않는다.

해시 테이블, HTTP 서버, TTL, 상품 API, DBMS와 해당 기능 테스트는 이번 작업에 포함하지 않는다. 빈 소스·테스트 경로는 `.gitkeep`으로 보존한다.

## 검증 결과

| 검증 | 결과 | 확인 내용 |
|---|---|---|
| `java -version` — 선택한 JDK | 통과 | Temurin OpenJDK 25.0.5, macOS aarch64 |
| `mini-redis/`에서 `./mvnw --version` | 통과 | Apache Maven 3.9.11, Java 25.0.5 |
| `mini-redis/`에서 `./mvnw test` | 통과 | `BUILD SUCCESS`, `No tests to run.`. 빌드 설정 로드 검증이며 기능 테스트 통과를 뜻하지 않음 |
| 문서 링크·요구사항 연결표 | 통과 | 상대 링크/앵커의 대상, 10개 요구사항 ID와 연결표의 대응 확인 |
| 공유 OMX TOML·Maven XML·Wrapper 구문 | 통과 | TOML/XML 파싱, Unix Wrapper 셸 구문, 실행 권한 확인 |
| Git 줄바꿈·스테이징 검사 | 통과 | `.gitattributes`로 Unix Wrapper는 LF, Windows Wrapper는 CRLF로 체크아웃하도록 지정. Git 인덱스는 정규화하고 `git diff --cached --check`로 확인 |
| `omx doctor` — 샌드박스 밖 재검사 | 경고 포함 통과 | 19개 통과, 경고 1개, 실패 0개. CLI·Node·설정·훅·프롬프트·스킬·세션 연결 확인 |
| 기능 단위 테스트·외부 HTTP 테스트·성능 측정 | 미실행 | 아직 구현하지 않은 후속 단계이며 0단계 완료 기준 아님 |

## 실행 환경과 남은 제약

- 시스템 기본 `java`는 17.0.5다. 이번 검증은 임시 디렉터리의 Temurin JDK 25.0.5를 `JAVA_HOME`으로 지정했다. 시스템 기본 JDK를 변경하거나 JDK 바이너리를 저장소에 추가하지 않았다.
- 재실행할 때는 Java 25 JDK를 설치/선택하고 아래 명령을 실행한다. 이번 검증에서는 저장소 밖의 기존 Maven 배포판·의존성 캐시를 `MAVEN_USER_HOME`과 `MAVEN_OPTS`의 `maven.repo.local`로 지정했다. 캐시가 없는 환경은 첫 실행에 네트워크 접근이 필요하다.

```sh
# JAVA_HOME이 사용할 Java 25 JDK를 가리키는 환경에서 실행
"$JAVA_HOME/bin/java" -version
cd mini-redis
./mvnw --version
./mvnw test
```

- Maven 실행 중 Guice의 `sun.misc.Unsafe` 사용에 대한 JDK 경고가 출력되지만 빌드는 성공했다. 해당 경고를 없애기 위한 빌드 도구 업그레이드는 이번 범위에 포함하지 않았다.
- OMX 경고는 프로젝트 전용 `AGENTS.md`에 생성 계약 마커가 없다는 내용이다. 현재 작업 지침은 그대로 보존했다. 관리 섹션 복구가 필요하면 설치된 doctor 지침에 따라 설정·훅·사용자 지침 보존 범위를 확인한 뒤 공식 merge 경로를 사용해야 한다. 경고 제거만을 위해 마커를 임의 삽입하거나 설정을 덮어쓰지 않았다.
- 샌드박스 안 첫 OMX 진단은 프로세스 식별 제한으로 세션 연결 검사에 실패했다. 권한이 있는 환경에서 같은 진단을 재실행하여 실패 0개를 확인했다. Team/tmux 모드의 실제 실행은 검증하지 않았다.
- Windows용 `mvnw.cmd`는 포함하지만 Windows 실행 검증은 하지 않았다. 별도 lint/정적 분석 도구는 아직 설정하지 않았으며, 이번 단계에서는 문서·설정 파싱·Wrapper 구문 검사로 확인했다.
- 커밋/Push는 작업 저장이며, Jira 완료 전환은 팀 규칙에 따라 통합 PR의 기본 브랜치 병합과 완료 기준 충족 후 별도로 진행한다.
