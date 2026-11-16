# Mini DBMS 아키텍처

```text
SQL 파일 → CLI Main ─┐
                     ├→ SqlParser → SqlStatement → SqlEngine → Database → TableFile(products.jsonl)
POST /query ────┘                                         └→ B+ 트리(id → 바이트 위치/길이)
```

SQL 문법·정적 스키마 검증은 직접 작성한 SqlParser, 실행 순서는 SqlEngine, 파일/ID/잠금 생명주기는 Database, JSON Lines 바이트 처리는 TableFile이 담당한다. HTTP/CLI는 저장 형식이나 인덱스를 직접 만지지 않는다. Product는 고정 레코드, QueryResult는 INSERT affectedRows/insertedId 또는 SELECT rows를 JSON으로 전달한다.

## 파일 형식

데이터 디렉터리에는 `.minidbms.lock`(소유권)과 `products.jsonl`(테이블)이 있다. UTF-8 JSON 객체 하나와 LF가 행 하나다. JSON 내부의 개행은 이스케이프되므로 물리적 한 줄을 유지한다.

```json
{"id":1,"name":"사과🍎","price":1200}
{"id":2,"name":"","price":0}
```

필드는 정확히 id/name/price이며 타입·값·중복 필드를 검증한다. 시작 시 모든 행과 증가 ID를 확인해 다음 ID를 복원한다. 행 포인터는 LF를 제외한 `(바이트 오프셋, JSON 바이트 길이)`이고 Unicode 문자 수가 아니다. 버퍼 순회로 전체 스캔하고, 포인터 조회는 FileChannel 위치 읽기를 사용한다.

각 행은 부분쓰기를 반복해 끝까지 append한다. 정상 종료에서 force(true), 실패하면 추가 쓰기를 차단한다. 자동 tail repair/트랜잭션/WAL은 없다. 정상 종료 사이의 다른 프로세스 조회를 검증하지만 전원 장애·디스크 고장 보장은 아니다.

## 소유권·동시성 경계

DirectoryLock은 JVM registry로 같은 실제 잠금 파일의 중복 개방을 채널 생성 전에 거절하고 OS FileLock으로 다른 프로세스를 막는다. 잠금 파일과 테이블 채널을 분리해 테이블 스캔 채널 종료가 소유권 잠금을 풀지 않게 한다. 신뢰된 로컬 디렉터리, 활성 파일 외부 rename/unlink/직접 쓰기 금지 전제다.

Database의 공통 모니터가 명령 하나의 ID 발급/파일 변경을 보호한다. 이후 인덱스 반영도 같은 경계에서 수행한다. 다중 SELECT/INSERT의 SQL파일 전체 트랜잭션은 아니다. B+ 트리는 메모리 전용으로 파일을 진실 원천으로 삼아 시작 시 재구축한다. 내부 구분 키는 오른쪽 자식의 최소 키이며 upper-bound로 자식을 선택한다. leaf는 정렬 키/행포인터와 next, 내부노드는 구분키/자식 배열을 갖는다. overflow 시 leaf는 양분·구분키복사, 내부노드는 중간키승격·양분하며 루트분할은 새루트를 만든다. 중복키는 값을교체하고 크기를늘리지 않는다. 전체검증 함수로 점유율/구분키/범위/leaf동일깊이/연결/크기를 확인한다. HTTP 스레드 풀은 같은 엔진/Database 인스턴스를 공유한다.

## 프로젝트

- mini-dbms: 독립 Java25 Maven 프로젝트, 같은 버전의 기존 Redis 빌드 도구 재사용.
- src/main/java/minidbms: SQL/파일/CLI, index/http/benchmark.
- src/test/java/minidbms: 파서·파일·별도 JVM, 인덱스/실제 HTTP.
- docs/mini-dbms: DBMS 전용 완료 기준과 검증·측정 기록.
- mini-dbms/data와 mini-dbms/target은 Git에 포함하지 않는다. mini-redis와 같은 저장소에 배치하지만 Maven 의존성과 소스·데이터를 공유하지 않는다. Redis 없이 DBMS 테스트와 CLI/HTTP를 실행할 수 있다.

## HTTP 실행과 한계

HttpMain은 Database 생명주기를 소유하고, QueryHttpServer는 HTTP/작업스레드만 소유한다. 서버 시작 전에 DB검증·인덱스구축을 마치며, 정상 종료는 서버/worker 종료 후 DB.force/close 순서다. 포트/입력 설정 오류는 파일을 열기 전에 검증하고, 시작 실패 시 자원을 반환한다.

QueryHttpServer는 엄격 UTF8/JSON→단일SQL변환→SqlEngine.executeOne→QueryResult JSON을 담당한다. QueryLimits를 Database에 전달해 조회 중 행수/보수적바이트예산을 확인한다. 예산 초과는 읽기 전용 오류이며 파일/인덱스를 변경하지 않는다. CLI는 같은 엔진에 무제한 예산을 사용한다. 저장소 실패는 원인 로그+503, 예상 외 오류는 원인 로그+500으로 보내며 이미 응답 중이면 예외를 전파한다.

공통 모니터는 단순한 단일프로세스 일관성 선택이다. 같은 프로세스에서 외부 HTTP 요청은 병렬 도착하지만 DB작업은 명령 단위로 직렬화된다. 장시간 스캔 동안 쓰기도 대기한다. 고정8개 스레드의 대기열은 무제한이며 Slow-client/CPU쿼리 제한은 없다. 대량 결과는 HTTP예산으로 메모리 사용을 제한하지만 운영준비 상태를 뜻하지 않는다.
