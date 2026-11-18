# Mini DBMS 요구사항과 진행 기준

DBMS-01~DBMS-03 구현을 이 프로젝트의 `mini-dbms/`에서 독립적으로 관리한다. Redis와 소스·빌드·데이터를 공유하지 않는다. 사용자 개인 기준 브랜치는 `ym-mini-dbms`, Jira 에픽은 [KJF-58](https://kylekim0868.atlassian.net/browse/KJF-58)이다. 최초 기준은 `c5381b323311e6738fc0f48afefd397ba0db3e2d`이며 단계별 구현은 전용 브랜치에서 전달했다. 폴더·문서 통합 결과는 [개인 통합 기록](../workspace-verification.md)에 따라 ym-mini-dbms 대상 PR #40으로 관리한다. main 통합이나 Jira 완료 전환은 포함하지 않는다. KJF-64에서 ID 범위 조회와 선택형 HTTP 쿼리 캐시를 추가한다.

## 현재 범위와 구현 순서

| ID | 요구사항 | 완료 기준 | Jira | 브랜치 |
|---|---|---|---|---|
| DBMS-01 | 직접 구현한 SQL 처리기·테이블 파일·CLI | INSERT 성공 후 새 JVM SELECT로 같은 행 조회, 파서/파일/오류 검증 | [KJF-59](https://kylekim0868.atlassian.net/browse/KJF-59) | `feat/KJF-59-ym-sql-file` |
| DBMS-02 | 직접 구현한 메모리 B+ 트리 ID 인덱스 | leaf/internal/root 분할, 재구축, 100만건 이상 삽입 후 ID조회·비인덱스 검색 실측 | [KJF-60](https://kylekim0868.atlassian.net/browse/KJF-60) | `feat/KJF-60-ym-bplus-index` |
| DBMS-03 | HTTP API와 저장소 동시성 | 실제 병렬 HTTP INSERT에서 ID 중복 없이 파일·인덱스 일치, 재시작 후 보존 | [KJF-61](https://kylekim0868.atlassian.net/browse/KJF-61) | `feat/KJF-61-ym-http-concurrency` |
| DBMS-04 | B+ 트리 체크포인트와 증분 복구 | 단건·범위·다음 ID 일치, 꼬리 복구와 손상 시 재구축, 동일 데이터의 재시작 실측 | [KJF-65](https://kylekim0868.atlassian.net/browse/KJF-65) | [feat/KJF-65-ym-index-checkpoint](https://github.com/kylekim0868-cpu/krafton-jungle-framework2/tree/feat/KJF-65-ym-index-checkpoint) |

## 이슈·브랜치 연결

요구사항 하나에 Task 하나·브랜치 하나를 사용한다. 새 브랜치는 구현 전에 즉시 publish하고 원격 SHA/upstream을 검증한다. 이후 단계는 이전 단계의 검증 커밋에서 분기한다. 첫 PR은 ym-mini-dbms, 이후 PR은 직전 단계 브랜치를 대상으로 한다.

## 고정 스키마와 SQL 계약

스키마 정의가 별도로 없어 제안한 `products(id, name, price)`를 이번 구현의 고정 계약으로 사용한다.

- id: 자동 발급 양수 long, 1부터 증가하며 재시작 시 파일에서 복원한다. INSERT에 직접 지정하지 않는다.
- name: 문자열, 빈 문자열과 Unicode 허용. SQL 문자열 내 작은따옴표는 `''`로 이스케이프한다.
- price: 0 이상 long. 부동소수점/문자열/null은 거절한다.
- 테이블 파일: 데이터 디렉터리의 products.jsonl. 다른 테이블/컬럼과 CREATE TABLE은 지원하지 않는다.

```sql
INSERT INTO products (name, price) VALUES ('사과', 1200);
INSERT INTO products (price, name) VALUES (900, 'O''Brien; 배');
SELECT * FROM products;
SELECT name, price FROM products WHERE id = 1;
SELECT id FROM products WHERE name = '사과' LIMIT 10;
SELECT id FROM products WHERE id BETWEEN 10 AND 20;
```

키워드·테이블·컬럼 이름은 대소문자 구분 없이 처리하고 문자열 값은 보존한다. `--` 줄 주석, 여러 SQL 명령, 마지막 세미콜론 생략을 허용한다. SELECT는 컬럼 목록/별표, 한 개의 동등 WHERE(id/name/price) 또는 ID BETWEEN 범위 조건, 선택적인 양수 LIMIT를 지원한다. 반환 순서는 삽입(ID) 순이다. AND/OR/JOIN/UPDATE/DELETE/DDL/트랜잭션은 제외한다.

SQL 파일 전체를 파싱·스키마 검증한 뒤 실행하므로 뒤에 문법 오류가 있어도 앞 INSERT를 실행하지 않는다. 단, 실행 중 I/O 오류까지 롤백하는 다중 명령 트랜잭션은 아니다. 한 행의 직렬화된 JSON은 최대1MiB다. 이 바이트 한도는 append 전 런타임 검사이므로 뒤의 과대 행 오류가 앞의 정상 INSERT까지 롤백하지 않는다.

## 검증과 제약

Java25, Jackson3.3.0, JUnit5.13.4, MavenWrapper를 사용한다. `mini-dbms/`에서 `./mvnw test`. SQL 파서/B+트리는 외부 SQL·인덱스 라이브러리를 사용하지 않는다. CLI/HTTP는 같은 SQL 엔진·테이블 파일을 재사용한다. HTTP는 DBMS-03에서 제공한다.

성능 결과에는 데이터·요청키·워밍업·표본수·p50/p95·처리량·행수 및 일치 검증을 기록한다. DBMS-02의 기존 기록은 ID 조건과 name 조건을 비교한 자료다. KJF-64는 같은 ID 조건을 인덱스 사용 여부만 바꿔 비교한다. 실제 측정만 기록하며 운영 최대 성능으로 일반화하지 않는다.

FileChannel append 후 명령 성공, 정상 close에서 force(true)한다. 매 INSERT fsync/WAL/전원 장애 원자성은 보장하지 않는다. 부분 쓰기/손상된 행/잘린 마지막 행은 자동 삭제하지 않고 재개를 거절한다. 데이터 디렉터리는 한 프로세스가 소유하며, 활성 파일을 외부에서 수정/교체하지 않는다.

[아키텍처](architecture.md) · [팀 규칙](../../TEAM_DEVELOPMENT_RULES.md) · [DBMS-01 검증](dbms-01-verification.md)

## DBMS-02 인덱스 계약

- maxKeys 기본63인 직접 B+ 트리를 사용한다(최적 설정이라는 주장은 아님). 키 long/값 행 포인터, leaf만 실제 값을 저장하며 leaf간 next 연결을 유지한다. full leaf/internal/root 분할과 중복키 덮어쓰기를 지원한다. 행 삭제가 없어 트리 삭제/병합은 범위 밖이다.
- `WHERE id = 정수`는 PRIMARY_KEY_INDEX로 포인터 위치를 읽고, ID 범위는 PRIMARY_KEY_RANGE로 리프를 순회한다. name/price/조건 없는 조회는 TABLE_SCAN이다. QueryResult에 accessPath(APPEND/PRIMARY_KEY_INDEX/TABLE_SCAN)를 추가해 실제 경로를 관찰한다.
- DBMS-02는 시작할 때 검증한 JSONL에서 인덱스를 구축한다. DBMS-04에서 체크포인트 복원 경로를 추가한다. INSERT의 파일 append·인덱스 반영·ID 증가를 같은 모니터로 보호한다. 파일은 추가됐으나 인덱스 반영이 실패하면 이후 명령을 거절하고 재시작 검증이 필요하다.
- 실험은 새 디렉터리에100만행 삽입·정상 close 후 다시 열어 재구축한 뒤 동일20개 키·각조건 워밍업3회·단일스레드·동일행/컬럼/응답으로 ID검색과name검색을 교대 측정한다. 각 쿼리는 LIMIT1이며 선형탐색은 첫 일치까지 읽는다. JVM heap512MiB, 원시 ns 지연과p50/p95·동작시간합 기반처리량 기록. 다른 필드/접근방식 비교이며 동일 쿼리 계획 비교·운영 성능 보장이 아니다.

[DBMS-02 검증·100만행 실제 측정 기록](dbms-02-verification.md). 교정 후 새 데이터 디렉터리에서 재측정하고 별도 JVM 조회까지 검증했다.

## DBMS-03 HTTP 계약

- JDK HttpServer, loopback, 고정8개 요청 스레드가 같은 SqlEngine/Database를 공유한다. Database의 공통 모니터 안에서 ID발급·파일append·B+트리반영·ID증가를 직렬화한다. 파일/인덱스 조회도 같은 경계를 사용해 중간 상태가 보이지 않는다.
- `POST /query`, `Content-Type: application/json`, UTF8 JSON 객체 `{"sql":"SELECT * FROM products WHERE id=1;"}`. SQL은 정확히 한 명령만 허용하며 여러 명령은 실행 전에400으로 거절한다. CLI의 다중 명령 지원은 유지한다.
- 정상 INSERT/SELECT 모두200과 QueryResult(command/affectedRows/insertedId/rows/accessPath). 없는 ID는200/rows[].
- 잘못된 JSON·UTF8·sql필드/추가필드/중복필드/추가JSON은400 INVALID_REQUEST. SQL문법·미지원·여러명령은400 INVALID_SQL. 잘못된경로404, 메서드405/Allow POST, 미디어타입415, 본문1MiB초과413 REQUEST_TOO_LARGE. 잘린 HTTP본문의 전송 오류는400 REQUEST_READ_FAILED(연결이 살아있을 때)이며 DB를 고장 상태로 바꾸지 않는다.
- HTTP결과는 최대1000행, 완전한 저장 행 바이트 합의 보수적 예산(4MiB-8192)으로 제한한다. 작은 projection도 전체 행 크기로 계산한다. 초과하면413 RESULT_TOO_LARGE이며 조용히 잘라서 반환하지 않는다. SQL LIMIT 또는 좁은 WHERE로 줄일 수 있다. CLI는 이 HTTP예산을 적용하지 않는다.
- 결과 예산은 읽는 동안 검사해 무제한 행을 메모리에 모으지 않는다. 최종JSON도4MiB를 검사한다. 저장소 I/O 실패 이후는503 STORAGE_UNAVAILABLE, 이후 읽기/쓰기 모두 차단하고 재시작 검증을 요구한다.
- INSERT 응답이 유실되거나 연결이 끊어져도 이미 append됐을 수 있다. 요청ID/멱등성/다중명령트랜잭션은 없다. loopback예제이며 인증/TLS/부하차단/쿼리기한/운영제어는 범위 밖이다.

## KJF-64 — ID 범위 조회와 선택형 쿼리 캐시

- `WHERE id BETWEEN 정수 AND 정수`는 두 경계를 포함하고, 역순 범위는 빈 결과다. 범위는 B+ 트리 리프 연결로 순회하고 `PRIMARY_KEY_RANGE`를 보고한다. 기존 한도와 정렬을 적용한다.
- HTTP 서버의 선택적인 Redis 주소는 포트가 있는 loopback HTTP origin이다. SQL을 파싱한 뒤 인덱스를 쓰지 않는 SELECT만 대상으로 한다. 동일한 파싱 결과의 해시, 서버별 UUID, 저장소 버전으로 키를 만든다. DBMS가 소유권을 가진 동안 INSERT를 반영할 때 버전도 바뀌므로 새 읽기는 이전 캐시를 사용하지 않는다.
- Redis HTTP 요청은 전체 응답에 250ms 기한을 적용한다. 성공한 스캔 결과는 Redis의 요청 크기 한도에 들어갈 때 TTL 30초로 저장한다. Redis 오류는 DBMS 조회로 넘어가며 DBMS의 저장소 오류와 결과 한도는 적용한다.
- `X-Query-Cache`의 `HIT`는 캐시 응답, `MISS`는 캐시에서 찾지 못해 DBMS에서 읽은 응답, `BYPASS`는 캐시 대상 외 또는 Redis 오류다. 캐시가 비활성화된 실행에는 이 헤더가 없다.


## DBMS-04 인덱스 복구 계약

- `products.jsonl`이 원본이다. 정상 종료 시 JSONL을 동기화하고 데이터 디렉터리 소유권을 유지한 상태에서 `products.index`를 게시한다. 체크포인트가 현재 행을 모두 포함하면 파일을 다시 쓰지 않는다.
- 체크포인트의 버전·크기·행 포인터·체크섬을 검증하고 JSONL 접두부 해시를 확인한다. 일치하면 B+ 트리 상태를 복원하고 체크포인트 이후 행을 파싱해 다음 ID를 결정한다.
- 체크포인트를 사용할 수 없으면 전체 JSONL을 검증해 인덱스를 재구축한다. JSONL의 부분 행·손상·ID 불연속은 기존대로 시작을 거절한다.

[DBMS-04 검증과 재시작 실측](dbms-04-verification.md).
