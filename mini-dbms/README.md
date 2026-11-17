# Mini DBMS (+ Mini Redis 캐시)

Java 17 만으로 구현한 미니 DBMS 와, 그 앞에 임시 캐시로 붙인 미니 레디스.
실행 코드에는 외부 라이브러리가 없다 (테스트에만 JUnit 5 / AssertJ 사용).

| 단계 | 내용 | 핵심 클래스 |
|---|---|---|
| Phase 1 | SQL 처리기: SQL 파일 → 파싱 → 실행 → 파일 저장 (CLI) | `Tokenizer`, `Parser`, `SqlEngine`, `Table`, `Main` |
| Phase 2 | B+ 트리 인덱스: 자동 증가 id 를 인덱싱, 100만 건 성능 비교 | `BPlusTree`, `Benchmark` |
| Phase 3 | API 서버: 직접 만든 스레드 풀로 SQL 요청을 병렬 처리 | `ThreadPool`, `ApiServer` |
| 추가 | 미니 레디스: 느린 SELECT 결과를 잠시 기억하는 임시 캐시 | `HashTable`, `ConcurrentHashMap`, `TTLManager`, `CachedQueryService` |

## DBMS 와 미니 레디스의 구분

둘은 폴더, 저장 위치, 수명, API 경로가 모두 다르고, 서로의 코드를 import 하지 않는다.

| | DBMS | 미니 레디스 |
|---|---|---|
| 역할 | **진짜 데이터**를 보관한다 | DBMS 가 계산한 **결과의 임시 복사본**을 보관한다 |
| 폴더 | `src/main/java/com/minidbms/dbms/` | `src/main/java/com/minidbms/redis/` |
| 저장 위치 | 디스크 (`data/*.tbl`) | 메모리 |
| 서버를 끄면 | 남는다 | 사라진다 (AOF / RDB 없음) |
| 자료 구조 | 파일 + B+ 트리 인덱스 | 직접 만든 해시 테이블 (Separate Chaining) |
| 데이터 모양 | 테이블 (행과 컬럼, 타입 있음) | Key → Value (아무 값) |
| 사용 방법 | SQL (`POST /query`) | Key 단위 (`/api/redis`) |
| 지워지는 때 | 지워지지 않는다 | TTL 만료 시 (Lazy + Active Expiration), FLUSH 시 |
| 동시성 | 테이블마다 읽기-쓰기 락 1개 | 세그먼트 16개마다 읽기-쓰기 락 (Segment Lock) |
| 틀리면? | 안 된다. 항상 정확해야 한다 | 통째로 비워도 결과는 같고 속도만 느려진다 |

```
src/main/java/com/minidbms/
├── Main.java                  진입점. serve 에서 DBMS 와 미니 레디스를 각각 만들어 서버에 넘긴다
├── dbms/                      ── DBMS (redis 패키지를 모른다) ──
│   ├── sql/                   Tokenizer, Parser, Statement(AST)
│   ├── engine/                SqlEngine, Database, Table, QueryResult
│   ├── storage/               Schema, RowCodec (파일 형식)
│   ├── index/                 BPlusTree
│   ├── cli/  bench/           TablePrinter, Benchmark
│   └── DbException.java
├── redis/                     ── 미니 레디스 (dbms 패키지를 모른다) ──
│   ├── core/                  HashTable, ConcurrentHashMap, TTLManager
│   └── api/                   RedisController
└── server/                    ── 둘을 외부에 노출하는 HTTP 서버 ──
    ├── ApiServer, ThreadPool, Json
    └── CachedQueryService     DBMS 결과를 미니 레디스에 캐시하는 로직이 있는 유일한 클래스
```

`dbms/` 와 `redis/` 는 서로를 import 하지 않는다. 두 패키지를 모두 아는 파일은 셋뿐이다:
객체를 만들어 연결하는 `Main`, 경로에 따라 요청을 나눠 주는 `ApiServer`, 캐시 로직을 가진 `CachedQueryService`.

## 빠른 시작

JDK 17 이상이 필요하다. Windows PowerShell 에서는 `./gradlew` 대신 `.\gradlew.bat` 을 쓴다.

```bash
./gradlew build                      # 컴파일 + 테스트 + jar 생성
```

```bash
java -jar build/libs/mini-dbms-1.0.0.jar run examples/schema.sql    # 테이블 준비 (처음 한 번)
java -jar build/libs/mini-dbms-1.0.0.jar run examples/demo.sql      # Phase 1: SQL 파일 실행
java -jar build/libs/mini-dbms-1.0.0.jar repl                       # 대화형 셸
java -jar build/libs/mini-dbms-1.0.0.jar bench --rows 1000000       # Phase 2: 성능 비교
java -jar build/libs/mini-dbms-1.0.0.jar serve --port 8080 --threads 8   # Phase 3: API 서버 (+ 미니 레디스)
```

- 공통 옵션 `--data-dir DIR` 로 데이터 폴더를 바꿀 수 있다 (기본값 `data`, bench 는 `bench-data`).
- `serve` 의 `--cache-ttl 초` 는 느린 SELECT 결과를 캐시에 두는 시간이다 (기본 30, `0` 이면 쿼리 캐시 끔).
- 미니 레디스는 `serve` 에서만 동작한다. `run`, `repl`, `bench` 는 DBMS 만 사용한다.

## 전체 구조

```
   SQL 파일 / 키보드                    HTTP 요청                           벤치마크
         │                                │                                   │
     Main (CLI)              ApiServer + ThreadPool (워커 N개)             Benchmark
         │                  ┌─────────────┴──────────────┐                    │
         │            POST /query                    /api/redis               │
         │                  │                            │                    │
         │         CachedQueryService ──(get/put)──▶ RedisController          │
         │                  │                  │         │                    │
         │                  │                  ▼         ▼                    │
         │                  │           ┌─────────────────────────┐           │
         │                  │           │ 미니 레디스 (메모리)       │           │
         │                  │           │ ConcurrentHashMap        │           │
         │                  │           │  └ HashTable × 16 세그먼트 │           │
         │                  │           │ TTLManager (1초마다 청소)  │           │
         │                  │           └─────────────────────────┘           │
         ▼                  ▼                                                 ▼
   ┌───────────────────────────────────────────────────────────────────────────┐
   │ DBMS (디스크)    SqlEngine → Tokenizer → Parser → 실행                      │
   │                  Table = BPlusTree(id → 파일 위치) + users.tbl(한 줄 = 행)   │
   └───────────────────────────────────────────────────────────────────────────┘
```

## Phase 1 — SQL 처리기

### 지원하는 SQL

```sql
CREATE TABLE [IF NOT EXISTS] users (name TEXT, age INT, score REAL);
INSERT INTO users VALUES ('kim', 25, 3.5);
INSERT INTO users (name, age) VALUES ('lee', 31), ('park', 19);   -- 여러 행, 생략한 컬럼은 NULL
SELECT * FROM users;
SELECT name, age FROM users WHERE age >= 20 AND name != 'kim' LIMIT 10;
SELECT COUNT(*) FROM users WHERE score IS NOT NULL;
SELECT * FROM users WHERE id BETWEEN 10 AND 20;
```

- 타입: `INT`(64비트 정수), `REAL`(실수), `TEXT`. 별칭 `INTEGER`, `BIGINT`, `DOUBLE`, `FLOAT`, `VARCHAR(n)`, `STRING` 도 받는다.
- 비교: `=`, `!=`, `<>`, `<`, `<=`, `>`, `>=`, `IS NULL`, `IS NOT NULL`, `BETWEEN`. 조건 연결은 `AND` 만 지원한다.
- 문자열 안의 작은따옴표는 `''` 로 쓴다. `--` 뒤는 주석이다. 키워드와 이름은 대소문자를 구분하지 않는다.
- 과제 조건상 `CREATE TABLE` 은 필수가 아니지만, 스키마 파일을 손으로 만들지 않아도 되도록 추가했다.

### 처리 흐름

`SELECT name FROM users WHERE id = 3;` 을 예로 들면:

1. **Tokenizer** — 글자를 읽어 토큰으로 자른다: `select` `name` `from` `users` `where` `id` `=` `3` `;`
2. **Parser** — 문법 규칙대로 토큰을 읽어 객체로 만든다: `Select(table=users, columns=[name], where=[id EQ 3])`
3. **SqlEngine** — 테이블과 컬럼이 실제로 있는지, 타입이 비교 가능한지 검사하고 접근 경로를 고른다.
4. **Table** — 파일에서 행을 읽어 돌려준다.

파서는 재귀 하강 방식이다. 문법 규칙 하나가 메서드 하나(`select()`, `insert()`, `condition()`, `literal()`)에 대응한다.
문법 오류는 위치와 함께 알려 준다: `Syntax error at position 9: expected FROM but found 'FORM'`.

### 파일 저장 형식

테이블 하나가 파일 두 개다.

```
data/users.schema          data/users.tbl
-----------------          ------------------------
id INT                     1,kim,25,kim@example.com
name TEXT                  2,lee,31,\N
age INT                    3,park,19,\N
email TEXT                 4,O'Neil\, Jr.,40,\N
```

- 데이터 파일은 사람이 읽을 수 있는 텍스트이고, **뒤에만 붙여 쓴다(append-only)**.
- 필드 안의 쉼표, 줄바꿈, 역슬래시는 `\,` `\n` `\\` 로 이스케이프한다. 그래서 "한 줄 = 행 하나"가 항상 성립한다.
- `\N` 은 NULL 이다. 빈 문자열 `''` 과 구분된다.
- INSERT 한 문장은 전체 검증을 먼저 끝낸 뒤 한 번의 쓰기로 기록한다. 여러 행 중 하나라도 잘못되면 한 행도 저장되지 않는다.
- 쓰는 도중 프로그램이 죽어 마지막 줄이 잘렸다면, 다음 시작 때 그 조각만 잘라 내고 복구한다.

## Phase 2 — B+ 트리 인덱스

### 인덱스가 저장하는 것

행이 추가되면 id 가 자동으로 부여되고(`1, 2, 3, ...`), B+ 트리에 **`id → 그 행이 파일에서 시작하는 바이트 위치`** 가 등록된다.

```
WHERE id = 777777
  ① B+ 트리 검색 (4층만 내려감)  →  위치 33,812,345
  ② 파일의 그 위치로 바로 이동해 한 줄만 읽는다
```

인덱스가 없으면 파일의 첫 줄부터 100만 줄을 모두 읽으며 비교해야 한다.

### 트리 구조

```
                  [ 30 | 60 ]              ← 내부 노드: 길 안내용 키만 있다
                 /     |     \
     [10|20] → [30|40|50] → [60|70]        ← 리프 노드: 실제 (키, 값), 옆 리프와 연결
```

- **검색**: 루트에서 리프까지 한 길로 내려간다. 노드 안에서는 이진 탐색.
- **삽입**: 리프에 넣고, 꽉 차면 반으로 쪼개서 가운데 키를 부모로 올린다. 부모도 꽉 차면 위로 반복한다.
  - 리프 분할은 키를 부모로 **복사**한다 (데이터는 리프에 남아야 한다).
  - 내부 노드 분할은 키를 부모로 **이동**한다 (안내용 키는 한 곳에만 있으면 된다).
- **범위 검색**: 시작 키의 리프를 찾은 뒤 `next` 포인터로 옆 리프를 따라간다. `WHERE id BETWEEN ? AND ?` 에 쓰인다.
- 차수(노드당 최대 자식 수)는 128. 100만 건에서 높이는 4 다.
- 메모리 기반이므로 프로그램을 켤 때 데이터 파일을 한 번 훑어 다시 만든다.

### SQL 처리기와의 연결

`SqlEngine` 이 WHERE 절의 id 조건을 보고 접근 경로를 고른다. 결과에 어떤 경로를 썼는지 표시된다.

| WHERE 조건 | 접근 경로 | 동작 |
|---|---|---|
| `id = 값` | `INDEX_POINT` | B+ 트리로 한 행만 읽는다 |
| `id >`, `>=`, `<`, `<=`, `BETWEEN` | `INDEX_RANGE` | 리프를 따라 범위만 읽는다 |
| 그 외 (다른 컬럼, `id != 값`, 조건 없음) | `FULL_SCAN` | 파일 전체를 읽는다 |

어느 경로든 읽어 온 행에 WHERE 조건 전체를 다시 검사한다. 그래서 인덱스를 써도 안 써도 결과는 같고 속도만 다르다
(`id = 3 AND name = 'kim'` 은 인덱스로 한 행을 읽은 뒤 name 을 검사한다).

### 성능 테스트 결과 (100만 건)

`bench` 명령은 모든 작업을 SQL 문자열로 만들어 `SqlEngine` 에 넣는다. 즉 Phase 1 의 처리기를 그대로 거친다.

```
[INSERT] 1,000,000 rows in 0.77 s  (1,301,108 rows/s), index height = 4

+----------------------------------------+--------------+-------------+----------+
| query                                  | access path  | avg time    | queries  |
+----------------------------------------+--------------+-------------+----------+
| WHERE id = ?            (B+ tree)      | INDEX_POINT  |   0.0113 ms |    10000 |
| WHERE id BETWEEN ? AND ? (100 rows)    | INDEX_RANGE  |   1.3128 ms |      200 |
| WHERE email = ?         (other field)  | FULL_SCAN    | 463.9044 ms |        5 |
| WHERE id = ?            (index off)    | FULL_SCAN    | 280.6683 ms |        5 |
+----------------------------------------+--------------+-------------+----------+

Indexed lookup is 41,182x faster than a linear scan on another field.
```

- 측정 환경: Windows 11, Temurin JDK 17, 데이터 파일 약 43.7MB. 수치는 실행할 때마다 조금씩 달라진다.
- INSERT 는 한 문장에 1,000행씩 넣는 multi-row INSERT 1,000번이다.
- 마지막 줄은 같은 `WHERE id = ?` 를 인덱스만 끄고 실행한 것이다. 같은 쿼리에서 인덱스 유무의 차이를 보여 준다.

## Phase 3 — API 서버

프레임워크 없이 `ServerSocket` 과 직접 만든 스레드 풀로 구현했다.

```
클라이언트 ─TCP 연결─▶ [accept 스레드] ─작업 제출─▶ [대기 큐] ─▶ [워커 스레드 N개]
                        연결만 받는다               크기 제한      HTTP 요청 읽기 → 처리 → JSON 응답
```

### 스레드 풀 (`ThreadPool`)

- 워커 스레드 N개를 미리 만들어 두고 재사용한다. 워커는 큐에서 작업을 하나씩 꺼내 실행한다.
- 워커가 모두 바쁘면 작업은 큐에서 기다린다. **큐까지 가득 차면 즉시 `503` 으로 거절한다** (무한정 쌓다가 메모리가 터지는 것을 막는다).
- 작업 하나가 예외를 던져도 워커는 죽지 않는다.
- 종료할 때는 새 작업을 받지 않고 큐에 남은 작업을 마저 처리한다.

### 동시성 설계

| 공유 자원 | 보호 방법 |
|---|---|
| [DBMS] 테이블 데이터 파일 + B+ 트리 + 다음 id | 테이블마다 `ReentrantReadWriteLock` 1개 |
| [DBMS] 테이블 목록 | `java.util.concurrent.ConcurrentHashMap`, 생성은 `synchronized` |
| [미니 레디스] 해시 테이블 | 세그먼트 16개마다 `ReentrantReadWriteLock` (직접 만든 `ConcurrentHashMap`) |
| [서버] 스레드 풀 통계, 테이블 버전 번호 | `AtomicInteger` / `AtomicLong` |

- **SELECT 는 읽기 락** — 여러 스레드가 동시에 읽는다. 파일은 위치를 지정해 읽으므로(positional read) 서로 방해하지 않는다.
- **INSERT 는 쓰기 락** — 한 번에 한 스레드만. "id 부여 → 파일 쓰기 → 인덱스 등록"이 한 덩어리로 실행되어 id 가 겹치지 않는다.
- 락은 공정(fair) 모드라서 읽기 요청이 계속 들어와도 쓰기가 굶지 않는다.
- 읽는 쪽은 INSERT 한 문장이 반쯤 적용된 상태를 볼 수 없다.

## 미니 레디스 — 임시 캐시

### yj-mini-redis 와의 대응

`redis/core` 의 세 클래스는 yj-mini-redis 에서 그대로 가져왔다. 구조와 동작 흐름이 같다.

| yj-mini-redis | 여기 | 달라진 점 |
|---|---|---|
| `core/HashTable` | `redis/core/HashTable` | 없음 (패키지 이름만 다름) |
| `core/ConcurrentHashMap` | `redis/core/ConcurrentHashMap` | 없음 (패키지 이름만 다름) |
| `core/TTLManager` | `redis/core/TTLManager` | `@PostConstruct` / `@PreDestroy` / 로그를 뺐다. `start()` / `stop()` 을 직접 부른다 |
| `api/RedisController` | `redis/api/RedisController` | 같은 경로, 같은 메서드 이름, 같은 응답. `ResponseEntity` 대신 `Response` 를 돌려준다 |
| `config/AppConfig` | `Main.serve()` | Spring 이 빈을 만들던 일을 직접 `new` 로 한다 (`store` → `ttlManager.start()` 순서 동일) |
| `RedisController.slowDb()` | `server/CachedQueryService` | `Thread.sleep(500)` 으로 흉내 내던 느린 DB 자리에 진짜 DBMS 가 들어갔다 |
| `persistence/AOFManager`, `RDBManager` | 없음 | 임시 캐시라서 꺼지면 사라져도 된다. 오래 남길 데이터는 DBMS 가 맡는다 |
| Spring `@GetMapping` 등 | `ApiServer.redis()` | 경로와 메서드를 보고 `RedisController` 의 메서드를 직접 부른다 |

### 쿼리 캐시 (Cache-Aside)

`POST /query` 로 들어온 SELECT 는 `CachedQueryService` 를 거친다. yj-mini-redis 의 `slowDb()` 와 같은 순서다.

```
SELECT ─▶ ① store.get(key)  ── 있음 ──▶ 그대로 반환                         cache: HIT   (DBMS 를 건드리지 않음)
                 │ 없음
                 ▼
          ② engine.execute(select)       DBMS 가 파일을 읽는다
                 ▼
          ③ FULL_SCAN 이었으면 store.put(key, 결과, TTL)                     cache: MISS
             인덱스로 끝났으면 저장하지 않음 (이미 충분히 빠르다)              cache: BYPASS

INSERT ─▶ DBMS 실행 ─▶ 그 테이블의 버전 번호 +1 (캐시 무효화)
```

- **캐시 키**: `sql:users:v7:Select[table=users, ...]`. 파싱된 문장으로 만들므로 공백이나 대소문자만 다른 SQL 은 같은 키가 된다.
- **무효화**: `slowDb()` 의 가짜 DB 는 내용이 바뀌지 않았지만 진짜 DBMS 는 INSERT 로 바뀐다.
  그래서 키에 테이블의 버전 번호를 넣고, INSERT 가 끝나면 버전을 올린다. 이후 SELECT 는 새 버전의 키를 찾으므로 옛 결과는 다시 조회되지 않는다.
  남은 옛 항목은 TTL 이 지나면 `TTLManager` 가 지운다.
- 캐시를 통째로 비워도(`DELETE /api/redis`) 결과는 달라지지 않는다. 다음 SELECT 가 DBMS 에서 다시 읽을 뿐이다.

100만 건 테이블에서 측정한 결과 (인덱스를 못 타는 `WHERE email = ?`):

| 실행 | cache | 걸린 시간 |
|---|---|---|
| 첫 번째 | `MISS` | 390 ms (파일 전체 스캔) |
| 두 번째, 세 번째 | `HIT` | 0.1 ~ 0.5 ms |
| INSERT 직후 | `MISS` | 289 ms (최신 데이터를 다시 읽음) |
| 그 다음 | `HIT` | 0.09 ms |

### 레디스 직접 사용 (`/api/redis`)

DBMS 테이블과 무관한 임시 Key-Value 저장소로도 쓸 수 있다. 로그인 토큰, 조회수처럼 잠깐 쓰고 버릴 값에 맞다.

```bash
curl -X POST http://localhost:8080/api/redis -H "Content-Type: application/json" -d "{\"key\":\"k1\",\"value\":\"v1\",\"ttl\":60}"
```

```bash
curl http://localhost:8080/api/redis/k1
```

`GET /api/redis` 로 전체 목록을 보면 직접 넣은 키와 쿼리 캐시(`sql:` 로 시작하는 키)가 함께 보인다.

## API

| 구분 | Method | Path | 설명 |
|---|---|---|---|
| DBMS | POST | `/query` | SQL 실행. 본문은 SQL 텍스트 그대로, 또는 `{"sql": "..."}` (`Content-Type: application/json`) |
| DBMS | GET | `/tables` | 테이블 목록, 스키마, 행 수, 인덱스 높이 |
| 미니 레디스 | POST | `/api/redis` | 저장/수정 `{"key":"k1","value":"v1","ttl":60}` (ttl 생략 시 만료 없음) |
| 미니 레디스 | GET | `/api/redis/{key}` | 단건 조회 (없거나 만료 시 404) |
| 미니 레디스 | DELETE | `/api/redis/{key}` | 단건 삭제 |
| 미니 레디스 | GET | `/api/redis` | 전체 목록 + 통계 + 버킷 분포 |
| 미니 레디스 | DELETE | `/api/redis` | 전체 초기화 (FLUSH) |
| 서버 | GET | `/stats` | 스레드 풀 상태 + 쿼리 캐시 hit / miss |
| 서버 | GET | `/health` | 서버 상태 확인 |

```bash
curl -X POST http://localhost:8080/query -d "SELECT * FROM users WHERE name = 'kim'"
```

```json
{"ok":true,"results":[{"type":"SELECT","columns":["id","name","age","email"],
  "rows":[[1,"kim",25,"kim@example.com"]],"rowCount":1,"accessPath":"FULL_SCAN",
  "truncated":false,"cache":"MISS","message":"1 row","elapsedMs":0.213}]}
```

- 한 요청에 여러 문장을 세미콜론으로 이어 보낼 수 있고, 결과는 `results` 배열에 순서대로 담긴다.
- `/query` 의 오류 응답은 `{"ok":false,"error":"..."}`, `/api/redis` 의 오류 응답은 yj-mini-redis 와 같은 `{"error":"..."}` 형태다.

| 상태 코드 | 상황 |
|---|---|
| 200 / 201 | 성공 / 레디스에 저장됨 |
| 400 | SQL 문법 오류, 없는 테이블/컬럼, 타입 불일치, 빈 본문, 잘못된 JSON, 레디스 key/value 누락 |
| 404 / 405 | 없는 경로나 없는 레디스 키 / 지원하지 않는 메서드 |
| 411 / 413 / 431 | Content-Length 없음 / 본문 1MB 초과 / 헤더 16KB 초과 |
| 408 | 요청을 10초 안에 다 보내지 않음 |
| 503 | 워커와 대기 큐가 모두 참 |

- SELECT 한 번이 돌려주는 행은 최대 10,000개다. 넘으면 `truncated: true` 로 표시한다.

## 테스트

```bash
./gradlew test
```

82개 테스트가 있다. 결과 보고서는 `build/reports/tests/test/index.html` 에 생성된다.

| 구분 | 테스트 | 종류 | 검증 내용 |
|---|---|---|---|
| DBMS | `ParserTest` | 단위 | 토큰 분리, 각 문장 파싱, 문법 오류와 위치 |
| DBMS | `RowCodecTest` | 단위 | 쉼표/줄바꿈/역슬래시/한글/NULL 의 저장과 복원, 깨진 줄 감지 |
| DBMS | `BPlusTreeTest` | 단위 | 순차/무작위/역순 삽입을 `TreeMap` 과 대조, 범위 검색, 100만 건 높이 |
| DBMS | `SqlEngineTest` | 기능 | INSERT/SELECT 전체 흐름, 접근 경로 선택, 인덱스 유무 결과 동일, 재시작 복구, 잘린 파일 복구 |
| 미니 레디스 | `HashTableTest` | 단위 | yj-mini-redis 의 테스트 그대로: put/get/remove, 충돌 체이닝, 리사이징 |
| 미니 레디스 | `ConcurrentHashMapTest` | 단위 | yj-mini-redis 의 테스트 그대로: 100 스레드 동시 put, 혼합 실행, 데드락 없음 |
| 미니 레디스 | `TTLManagerTest` | 단위 | yj-mini-redis 의 테스트 그대로: Lazy / Active Expiration |
| 서버 | `ThreadPoolTest` | 단위 | 동시 실행 수 제한, 큐가 차면 거절, 작업 실패 후에도 동작, 종료 시 큐 비우기 |
| 서버 | `CachedQueryServiceTest` | 기능 | MISS → HIT, INSERT 시 무효화, 테이블별 무효화, TTL 만료, 캐시를 비워도 결과 동일 |
| 서버 | `ApiServerTest` | 기능 | 실제 포트에 서버를 띄워 HTTP 로 검증. `/query`, `/api/redis` CRUD, 32개 클라이언트 동시 INSERT, 503 |

## 한계

- `UPDATE`, `DELETE`, `JOIN`, `ORDER BY`, `OR`, 집계 함수(`COUNT(*)` 제외)는 지원하지 않는다.
- 인덱스는 id 컬럼에만 있다. 다른 컬럼 조건은 항상 선형 탐색이다 (그래서 그 결과를 미니 레디스가 캐시한다).
- 인덱스가 메모리에만 있어서 시작할 때 데이터 파일을 한 번 훑는다 (100만 건 기준 1초 미만).
- INSERT 는 OS 버퍼까지만 쓴다. 매번 디스크 동기화(fsync)를 하지 않으므로, 정전처럼 OS 가 함께 죽는 경우 마지막 쓰기가 유실될 수 있다. 정상 종료 시에는 동기화한다.
- 트랜잭션이 없다. 여러 문장을 한 요청으로 보냈을 때 중간에 실패하면 앞 문장은 반영된 채 남는다 (응답의 `completedStatements` 로 알려 준다).
- 미니 레디스에는 최대 크기 제한이 없다 (yj-mini-redis 와 같다). TTL 없이 넣은 키는 지우거나 FLUSH 할 때까지 메모리에 남는다. 쿼리 캐시 항목은 항상 TTL 이 있어서 저절로 사라진다.
- 쿼리 캐시는 한 서버 프로세스 안에서만 유효하다. 서버가 켜진 동안 데이터 파일을 다른 프로세스(CLI 등)로 바꾸면 캐시가 그 변경을 알지 못한다.
- HTTP 는 요청마다 연결을 닫는다 (keep-alive, chunked 본문, HTTPS 미지원).
