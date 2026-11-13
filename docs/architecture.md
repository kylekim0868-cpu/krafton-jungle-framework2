# Mini Redis·Mini DBMS 아키텍처

Mini Redis는 직접 만든 해시 테이블을 HTTP로 제공한다. 상품 API는 조회 결과를 캐싱하고, Mini DBMS는 후속 단계에서 상품 데이터의 원본을 맡는다. 현재는 Mini Redis를 먼저 구현하고 테스트용 원본으로 상품 API의 캐시 동작을 검증한다. [단계별 완료 기준](requirements.md#현재-범위와-구현-순서)

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

선택 기능인 AOF는 저장소의 쓰기 경계에 연결한다. 저장소가 로그 기록·동기화와 메모리 반영 순서를 관리하고, 재시작할 때는 HTTP 요청을 받기 전에 로그를 재생한다.

## 폴더 구조

아래는 목표 구조다. 설정 단계에서는 `mini-redis/`의 빌드 설정과 소스·테스트 경로를 마련한다. Java 파일과 후속 모듈은 각 기능에 착수할 때 추가한다.

```text
저장소 루트
├── AGENTS.md                    Codex 작업 순서와 검증 지침
├── TEAM_DEVELOPMENT_RULES.md     개인 작업과 팀 통합 규칙
├── README.md                    프로젝트 소개와 문서 안내
├── docs/
│   ├── requirements.md          요구사항과 단계별 완료 기준
│   ├── architecture.md          모듈 구조와 데이터 흐름
│   └── redis-00-verification.md  0단계 설정 검증 결과와 제약
├── mini-redis/
│   ├── pom.xml                  0단계 Maven 설정
│   ├── mvnw, mvnw.cmd           Maven Wrapper
│   ├── .mvn/wrapper/            Maven Wrapper 설정
│   └── src/
│       ├── main/java/          1단계 해시 테이블·저장소, 2단계 HTTP 서버
│       └── test/java/          JUnit 저장소 단위 테스트와 HTTP 기능 테스트
├── examples/
│   └── product-api/            4단계 외부 캐시 사용 예제
└── mini-dbms/
    └── README.md                후속 구현 범위 기록
```

`src/main/java/` 아래에서 해시 테이블과 저장소는 `store` 패키지에, HTTP 서버와 요청 처리는 `http` 패키지에 둔다. 테스트 패키지는 검증할 코드의 패키지와 맞춘다. AOF를 채택하면 로그 코드를 `persistence` 패키지에 둔다. Mini DBMS의 Maven 설정과 소스 구조는 해당 프로젝트에 착수할 때 추가한다.

## 기술 스택과 실행

- Java 25 LTS와 Maven Wrapper를 사용한다.
- HTTP 서버는 JDK `HttpServer`, JSON 처리는 Jackson, 테스트는 JUnit을 사용한다.
- Maven Wrapper를 구성한 뒤 `mini-redis/`에서 `./mvnw test`를 실행한다. HTTP 기능 테스트는 서버를 기동해 별도 HTTP 클라이언트가 실제 요청과 응답을 확인한다.
- 상품 API 연동 시 Mini Redis와 상품 API는 독립 프로세스로 실행한다. Mini DBMS 연동 단계에서는 DBMS도 별도 프로세스로 실행한다.
