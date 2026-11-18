# KJF-64 — ID 범위 조회와 선택형 Redis 쿼리 캐시

[KJF-64](https://kylekim0868.atlassian.net/browse/KJF-64) · [작업 브랜치](https://github.com/kylekim0868-cpu/krafton-jungle-framework2/tree/feat/KJF-64-ym-redis-dbms-integration)

`ym-mini-dbms`의 `6d5101d`에서 원격 브랜치를 먼저 게시하고 기준 SHA와 upstream을 확인했다. YM DBMS 선행 이슈는 KJF-60·61이다. YJ `9f7bd74`에서 [B+ 트리 리프 순회](https://github.com/kylekim0868-cpu/krafton-jungle-framework2/blob/9f7bd74a36f4b423d5f140959c0a11d7246f6e8d/mini-dbms/src/main/java/com/minidbms/dbms/index/BPlusTree.java#L111-L128), [동일 ID SQL의 인덱스 비교](https://github.com/kylekim0868-cpu/krafton-jungle-framework2/blob/9f7bd74a36f4b423d5f140959c0a11d7246f6e8d/mini-dbms/src/main/java/com/minidbms/dbms/bench/Benchmark.java), [캐시 버전 방식](https://github.com/kylekim0868-cpu/krafton-jungle-framework2/blob/9f7bd74a36f4b423d5f140959c0a11d7246f6e8d/mini-dbms/src/main/java/com/minidbms/server/CachedQueryService.java#L85-L126)을 가져왔다. YM 파일 위치·길이와 HTTP 행·바이트 한도를 사용하고, 캐시는 기존 Mini Redis의 HTTP API를 호출한다.

## 확인 결과

- Java 25.0.5.1 / Maven 3.9.11의 `cd mini-dbms && ./mvnw -B -o test`에서 **79개 통과**, 실패·오류·생략 0. 여러 리프에 걸친 범위, 역순 범위, LIMIT, HTTP 한도, 재시작 후 인덱스 재구축 및 선형 탐색과의 결과 일치를 검증했다.
- 별도 Redis를 요구하지 않는 자동 테스트에서 캐시 MISS/HIT, INSERT와 캐시 응답의 경쟁, DB 재시작 시 키 분리, 잘못된 캐시 응답과 중단 시 직접 조회를 검증했다.
- 실제 Mini Redis와 Mini DBMS를 별도 프로세스로 실행해 같은 Unicode 이름 조회를 반복했다. MISS/HIT의 응답 본문이 같았고, INSERT 후 MISS에서 2행을 조회했다. ID 범위 조회는 캐시를 거치지 않았고, Redis를 유지한 채 DBMS를 재시작하면 MISS부터 다시 시작했다. Redis를 종료한 뒤에도 DBMS가 BYPASS와 같은 2행을 반환했다. 실행 명령은 [DBMS README](../../mini-dbms/README.md#id-범위-조회와-선택형-redis-캐시)에 있다.

## 100만 행 동일 SQL 비교

macOS 14.8.9 / aarch64 / Java 25.0.5.1+4-LTS-20, heap 512 MiB. 새 디렉터리에 100만 행을 넣고 종료·재시작으로 인덱스를 재구축했다. 같은 키에 `SELECT * FROM products WHERE id = ? LIMIT 1`을 실행하고 두 경로의 반환 행이 일치하는지 확인했다. 워밍업 3쌍 뒤 같은 20개 키를 두 경로로 교대 측정했다.

| 접근 경로 | p50 (ms) | p95 (ms) |
|---|---:|---:|
| ID 인덱스 | 0.056584 | 0.111125 |
| 동일 ID 조건을 선형 탐색 | 222.948583 | 356.494584 |

[키·원시 지연·재구축 및 실행 환경](benchmarks/kjf-64-same-sql-million.json) · [실행 명령](../../mini-dbms/README.md#100만행-성능-비교-재현)

단일 스레드의 로컬 실험이며 분모는 각 조회의 실행 시간이다. 동시 HTTP 처리량과 Redis 캐시의 지연은 이 수치에 포함되지 않는다.
