# Mini DBMS 요구사항과 진행 기준

사용자 요청은 기존 Redis 과정과 같이 Mini DBMS 전체를 순서대로 구현·검증·커밋·Push·PR 전달하는 것이다. Redis 소스는 수정하지 않는다. 사용자 제공 개인 기준 브랜치는 `ym-mini-dbms`, Jira 에픽은 [KJF-58](https://kylekim0868.atlassian.net/browse/KJF-58)이다. 처음 기준은 `c5381b323311e6738fc0f48afefd397ba0db3e2d`이다. main은 변경하지 않고, PR 병합과 Jira 완료 전환은 별도 요청/팀 절차다.

## 단계

| ID | 요구사항 | 완료 기준 | Jira | 브랜치 |
|---|---|---|---|---|
| DBMS-01 | 직접 구현한 SQL 처리기·테이블 파일·CLI | INSERT 성공 후 새 JVM SELECT로 같은 행 조회, 파서/파일/오류 검증 | [KJF-59](https://kylekim0868.atlassian.net/browse/KJF-59) | `feat/KJF-59-ym-sql-file` |
| DBMS-02 | 직접 구현한 메모리 B+ 트리 ID 인덱스 | leaf/internal/root 분할, 재구축, 100만건 이상 삽입 후 ID조회·비인덱스 검색 실측 | [KJF-60](https://kylekim0868.atlassian.net/browse/KJF-60) | 선행 단계 검증 후 생성 |
| DBMS-03 | HTTP API와 저장소 동시성 | 실제 병렬 HTTP INSERT에서 ID 중복 없이 파일·인덱스 일치, 재시작 후 보존 | [KJF-61](https://kylekim0868.atlassian.net/browse/KJF-61) | 선행 단계 검증 후 생성 |

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
```

키워드·테이블·컬럼 이름은 대소문자 구분 없이 처리하고 문자열 값은 보존한다. `--` 줄 주석, 여러 SQL 명령, 마지막 세미콜론 생략을 허용한다. SELECT는 컬럼 목록/별표, 한 개의 동등 WHERE(id/name/price), 선택적인 양수 LIMIT를 지원한다. 반환 순서는 삽입(ID) 순이다. AND/OR/JOIN/UPDATE/DELETE/DDL/트랜잭션은 제외한다.

SQL 파일 전체를 파싱·스키마 검증한 뒤 실행하므로 뒤에 문법 오류가 있어도 앞 INSERT를 실행하지 않는다. 단, 실행 중 I/O 오류까지 롤백하는 다중 명령 트랜잭션은 아니다. 한 행의 직렬화된 JSON은 최대1MiB다. 이 바이트 한도는 append 전 런타임 검사이므로 뒤의 과대 행 오류가 앞의 정상 INSERT까지 롤백하지 않는다.

## 검증과 제약

Java25, Jackson3.3.0, JUnit5.13.4, MavenWrapper를 사용한다. `mini-dbms/`에서 `./mvnw test`. SQL 파서/B+트리는 외부 SQL·인덱스 라이브러리를 사용하지 않는다. CLI/HTTP는 같은 SQL 엔진·테이블 파일을 재사용한다. HTTP는 DBMS-03에서 제공한다.

성능 결과에는 데이터·요청키·워밍업·표본수·p50/p95·처리량·행수 및 일치 검증을 기록한다. ID 인덱스와 name 선형탐색은 서로 다른 접근 방식/필드의 비교이지 같은 쿼리 실행 계획 비교라고 주장하지 않는다. 실제 측정만 기록하며 운영 최대 성능으로 일반화하지 않는다.

FileChannel append 후 명령 성공, 정상 close에서 force(true)한다. 매 INSERT fsync/WAL/전원 장애 원자성은 보장하지 않는다. 부분 쓰기/손상된 행/잘린 마지막 행은 자동 삭제하지 않고 재개를 거절한다. 데이터 디렉터리는 한 프로세스가 소유하며, 활성 파일을 외부에서 수정/교체하지 않는다.

[아키텍처](architecture.md) · [팀 규칙](../TEAM_DEVELOPMENT_RULES.md) · [DBMS-01 검증](dbms-01-verification.md)
