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

필드는 정확히 id/name/price이며 타입·값·중복 필드를 검증한다. 시작 시 유효한 체크포인트와 이후 행을 확인해 다음 ID를 복원한다. 체크포인트가 없으면 시작부터 모든 행을 확인한다. 행 포인터는 LF를 제외한 `(바이트 오프셋, JSON 바이트 길이)`이고 Unicode 문자 수가 아니다. 버퍼 순회로 전체 스캔하고, 포인터 조회는 FileChannel 위치 읽기를 사용한다.

각 행은 부분쓰기를 반복해 끝까지 append한다. 정상 종료에서 force(true), 실패하면 추가 쓰기를 차단한다. 자동 tail repair/트랜잭션/WAL은 없다. 정상 종료 사이의 다른 프로세스 조회를 검증하지만 전원 장애·디스크 고장 보장은 아니다.

## 소유권·동시성 경계

DirectoryLock은 JVM registry로 같은 실제 잠금 파일의 중복 개방을 채널 생성 전에 거절하고 OS FileLock으로 다른 프로세스를 막는다. 잠금 파일과 테이블 채널을 분리해 테이블 스캔 채널 종료가 소유권 잠금을 풀지 않게 한다. 신뢰된 로컬 디렉터리, 활성 파일 외부 rename/unlink/직접 쓰기 금지 전제다.

Database의 공통 모니터가 명령 하나의 ID 발급/파일 변경을 보호한다. 이후 인덱스 반영도 같은 경계에서 수행한다. 다중 SELECT/INSERT의 SQL파일 전체 트랜잭션은 아니다. B+ 트리는 메모리에 유지하며 재시작 시 JSONL과 체크포인트를 사용해 복원한다. 내부 구분 키는 오른쪽 자식의 최소 키이며 upper-bound로 자식을 선택한다. leaf는 정렬 키/행포인터와 next, 내부노드는 구분키/자식 배열을 갖는다. overflow 시 leaf는 양분·구분키복사, 내부노드는 중간키승격·양분하며 루트분할은 새루트를 만든다. 중복키는 값을교체하고 크기를늘리지 않는다. 전체검증 함수로 점유율/구분키/범위/leaf동일깊이/연결/크기를 확인한다. HTTP 스레드 풀은 같은 엔진/Database 인스턴스를 공유한다.

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

## 선택형 HTTP 쿼리 캐시

`QueryHttpServer`가 파싱한 문장에서 테이블 스캔 SELECT를 골라 `RedisQueryCache`로 전달한다. 캐시가 비어 있으면 `Database`가 기존 결과 한도로 실행한 뒤 별도 Redis에 TTL을 걸어 저장한다. 캐시 실험은 Redis 메모리 모드를 기준으로 한다. ID 단건과 범위 조회는 바로 DBMS를 실행한다. Redis는 별도 HTTP 서버며 Maven 의존성을 공유하지 않는다.

`Database`는 같은 락 안에서 INSERT의 파일 쓰기·인덱스 반영·ID 증가를 수행한다. 캐시 키에 이 상태의 버전을 포함하고, 히트 뒤 버전과 DB 가용성을 다시 확인한다. 같은 결과를 조회하던 중 새 행이 반영되면 이전 캐시 응답을 버리고 DBMS에서 조회한다. 서버별 UUID는 재시작 뒤 같은 버전에 도달해도 키가 충돌하지 않게 한다.


## B+ 트리 체크포인트

`IndexCheckpoint`는 B+ 트리의 정렬된 리프 항목을 `products.index`에 저장한다. 버전 1 헤더는 매직 값, 버전, 체크포인트 끝 오프셋, 행 수, 해당 JSONL 접두부의 SHA-256을 담는다. 이어서 ID 순으로 4바이트 행 길이와 전체 체크포인트 본문의 SHA-256을 쓴다. ID는 1부터 연속이므로 행 길이와 LF를 누적해 행 포인터를 복원한다.

시작할 때 DB는 파일 길이, 행 수, 포인터 경계와 본문 체크섬을 검사하고 B+ 트리를 복원해 구조를 검증한다. JSONL 접두부는 원시 바이트로 읽어 해시를 대조하고 체크포인트 이후만 JSON으로 파싱한다. 접두부 변경이나 체크포인트 손상이 발견되면 전체 JSONL 검증으로 넘어간다. 원본이 훼손됐으면 기동에 실패한다.

정상 close에서 JSONL 동기화가 끝나고 인덱스가 건강할 때만 임시 파일을 동기화한 뒤 원자적으로 교체한다. 교체 실패 시 경고를 남기고 다음 시작 때 이전 체크포인트나 전체 JSONL을 사용한다. 이 방식에는 재시작 시 접두부 읽기와 메모리 트리 생성 비용이 남는다. 변경이 있으면 정상 종료에서 접두부 해시와 체크포인트 쓰기 비용을 지불한다. 전원 차단 시 최신 INSERT 내구성은 기존 JSONL 정책을 따른다.
