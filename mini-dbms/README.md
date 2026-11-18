# Mini DBMS

직접 구현한 SQL 처리기·파일 저장, 메모리 B+ 트리, HTTP API를 제공하는 독립 Maven 프로젝트다. 아래 `cd mini-dbms`는 저장소 루트 기준이며, 이미 이 폴더라면 생략한다. [요구사항](../docs/mini-dbms/requirements.md), [아키텍처](../docs/mini-dbms/architecture.md), [작업 지침](../AGENTS.md), [팀 규칙](../TEAM_DEVELOPMENT_RULES.md)을 따른다. 사용자 개인 통합 브랜치는 ym-mini-dbms, 에픽은 KJF-58이다.

## 빌드·검증

Java25 JAVA_HOME이 설정된 셸에서:

```sh
cd mini-dbms
./mvnw test
./mvnw compile org.apache.maven.plugins:maven-dependency-plugin:3.11.0:copy-dependencies -DincludeScope=runtime
```

## 서로 다른 프로세스에서 저장·조회

같은 mini-dbms 디렉터리에서 다음 명령은 각각 새로운 JVM이다. data가 기존에 있으면 ID는 이어서 발급된다.

```sh
printf "INSERT INTO products (name, price) VALUES ('사과', 1200);\n" > /tmp/dbms-insert.sql
printf 'SELECT * FROM products;\n' > /tmp/dbms-select.sql
"$JAVA_HOME/bin/java" -cp 'target/classes:target/dependency/*' minidbms.Main data /tmp/dbms-insert.sql
"$JAVA_HOME/bin/java" -cp 'target/classes:target/dependency/*' minidbms.Main data /tmp/dbms-select.sql
```

빈 데이터 디렉터리 기준 insertedId1, SELECT rows에 id1/name사과/price1200. stdout은 UTF8 JSON Lines, 문법/입력 오류는 stderr SQL_ERROR 및 exit2, 저장소 오류는 STORAGE_ERROR 및 exit3이다. CLI 인자는 데이터 디렉터리/SQL UTF8 텍스트 파일이다. SQL지원 범위와 장애 한계는 요구사항을 확인한다.

## 검증 기록

- [DBMS-01](../docs/mini-dbms/dbms-01-verification.md): 파서/파일/새 JVM 조회, 39개 테스트.
- [DBMS-02](../docs/mini-dbms/dbms-02-verification.md): 직접 B+ 트리·재구축·100만행 실제 비교. 누적50개 테스트.
- [DBMS-03](../docs/mini-dbms/dbms-03-verification.md): 실제 HTTP·병렬512INSERT·파일/인덱스 일치·재시작. 누적75개 테스트.
- 모든 기능은 작업 브랜치에 전달하며 자동 PR 병합/Jira 완료 전환하지 않는다.

## HTTP 서버·직접 확인

mini-dbms 디렉터리에서 위 빌드를 마친 뒤 별도 터미널을 사용한다.

```sh
"$JAVA_HOME/bin/java" -cp 'target/classes:target/dependency/*' minidbms.http.HttpMain 8090 data
```

INSERT는 작은따옴표가 포함되므로 안전한 heredoc 형식을 사용한다:

```sh
curl -i http://127.0.0.1:8090/query -H 'Content-Type: application/json' --data-binary @- <<'JSON'
{"sql":"INSERT INTO products(name,price) VALUES ('사과',1200);"}
JSON
curl -i http://127.0.0.1:8090/query -H 'Content-Type: application/json' -d '{"sql":"SELECT * FROM products WHERE id=1;"}'
curl -i http://127.0.0.1:8090/query -H 'Content-Type: application/json' -d '{"sql":"SELECT * FROM products LIMIT 10;"}'
```

정상200, INSERT에 자동ID, SELECT에 rows와 accessPath가 나온다. 데이터가 기존에 있으면 ID는1이 아닐 수 있으므로 INSERT응답의 insertedId로 조회한다. 서버를 Ctrl+C로 종료한 뒤 같은 data로 CLI 또는 HTTP를 다시 실행하면 행과 다음ID를 복구한다. 같은 data를 두 프로세스에서 동시에 열지는 않는다.

HTTP는 요청당 단일SQL, 본문1MiB, 결과1000행/보수적 행바이트4MiB 예산이다. 큰 결과는413이며 LIMIT/WHERE로 줄인다. `SELECT` 미스는200/rows[]이다. 유효하지 않은JSON/SQL400, 미디어415, 저장소오류503. 자세한 오류와 메모리/내구성 한계는 요구사항을 따른다.

## ID 범위 조회와 선택형 Redis 캐시

```sql
SELECT id, name FROM products WHERE id BETWEEN 10 AND 20 LIMIT 5;
```

두 경계를 포함해 ID 오름차순으로 조회한다. B+ 트리의 시작 리프부터 다음 리프로 순회하며 `accessPath`는 `PRIMARY_KEY_RANGE`다.

캐시를 사용하려면 [Mini Redis의 메모리 모드](../mini-redis/README.md#http-서버-실행)를 먼저 실행하고, DBMS 실행 명령에 Redis 주소를 추가한다.

```sh
"$JAVA_HOME/bin/java" -cp 'target/classes:target/dependency/*' minidbms.http.HttpMain 8090 data http://127.0.0.1:8080
```

- 캐시는 HTTP의 테이블 스캔 SELECT에만 적용한다. 예를 들어 동일한 `SELECT id FROM products WHERE name = '사과'`를 두 번 보내면 첫 응답에 `X-Query-Cache: MISS`, 다음에 `HIT`가 나온다.
- `INSERT` 이후에는 새 데이터 버전의 키를 쓰고, DBMS가 재시작하면 키 공간을 새로 만든다. 캐시 항목은 30초 뒤 만료된다.
- 캐시가 시간 초과되거나 잘못 응답하면 DBMS로 조회하고 `BYPASS`를 표시한다. 두 인자로 시작하면 캐시를 사용하지 않는다. SQL·응답 본문 계약은 두 모드에서 같다.

## 100만행 성능 비교 재현

```sh
BENCH_DIR=$(mktemp -d /private/tmp/dbms-million.XXXXXX)
"$JAVA_HOME/bin/java" -Xmx512m -cp 'target/classes:target/dependency/*' minidbms.benchmark.IndexBenchmark "$BENCH_DIR" 1000000 20 3
```

기존 데이터에는 덧붙이지 않으며 실험용 파일은 BENCH_DIR에 보존한다. 결과JSON에 행수/구조/환경/워밍업/원시지연/p50/p95/동작지연합 처리량을 기록한다. 동일한 `WHERE id = ? LIMIT 1`을 인덱스 사용 모드와 선형 탐색 모드로 교대 실행한다. [KJF-64 실측과 재현 조건](../docs/mini-dbms/kjf-64-verification.md)을 참고한다.
