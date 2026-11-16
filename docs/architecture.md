# Mini DBMS 아키텍처

```text
SQL 파일 → CLI Main ─┐
                     ├→ SqlParser → SqlStatement → SqlEngine → Database → TableFile(products.jsonl)
후속 POST /query ────┘                                         └→ 후속 B+ 트리(id → 바이트 위치/길이)
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

Database의 공통 모니터가 명령 하나의 ID 발급/파일 변경을 보호한다. 이후 인덱스 반영도 같은 경계에서 수행한다. 다중 SELECT/INSERT의 SQL파일 전체 트랜잭션은 아니다. B+ 트리는 메모리 전용으로 파일을 진실 원천으로 삼아 시작 시 재구축한다. HTTP 스레드 풀은 같은 엔진/Database 인스턴스를 공유한다.

## 프로젝트

- mini-dbms: 독립 Java25 Maven 프로젝트, 같은 버전의 기존 Redis 빌드 도구 재사용.
- src/main/java/minidbms: SQL/파일/CLI, 후속 index/http/benchmark.
- src/test/java/minidbms: 파서·파일·별도 JVM, 후속 인덱스/실제 HTTP.
- docs: 단계별 완료 기준과 검증·측정 기록.
- 데이터/target은 Git에 포함하지 않는다. 기존 Redis 작업공간/소스는 이 worktree에 복사하지 않는다.
