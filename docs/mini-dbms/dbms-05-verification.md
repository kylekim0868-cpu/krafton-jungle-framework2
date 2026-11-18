# DBMS-05 읽기·쓰기 잠금 검증

[KJF-66](https://kylekim0868.atlassian.net/browse/KJF-66) · 기준 `ym-mini-dbms` / `c7f438568ae300d393e936a8da42f9f5d5b1f8d5`

## 정확성

- Java 25.0.5.1에서 `cd mini-dbms && ./mvnw -o test`를 실행했다. 기준 90개, 변경 후 96개가 통과했고 양쪽 모두 실패·오류·생략 0개다. 새 동시성 테스트 6개를 단독으로 5회 반복해 모두 통과했다. 별도 Mini Redis 모듈의 Maven 테스트도 137개가 통과했다.
- 스캔이 실행 중인 상태를 래치로 고정했다. 다른 SELECT와 revision은 끝날 수 있었고 INSERT와 close는 스캔이 끝날 때까지 기다렸다. INSERT가 파일을 쓰는 중에는 SELECT가 기다렸으며 완료 뒤 행과 새 revision을 읽었다.
- 진행 중인 스캔과 별개로 인덱스 읽기가 I/O 오류를 감지했을 때 다른 SELECT, revision, 이후 INSERT가 모두 실패했다.
- 3개 INSERT 작업과 3개 SELECT 작업을 동시에 실행했다. 조회의 ID 연속성, revision 경계, 재시작 후 240개 행의 인덱스·스캔 일치와 중복 없는 이름을 확인했다. 기존 실제 병렬 HTTP INSERT·캐시·저장소 오류 테스트도 통과했다.

## 처리량과 p95

Apple M1 Pro 8코어 / 16GB, Java 25.0.5.1, 힙 512MiB. 두 빌드에 동일한 30,000행 JSONL과 체크포인트를 사용했다. 워커는 같은 SQL을 준비한 뒤 동시에 반복 실행하며 매 요청 결과가 마지막 ID인지 검사했다. 스캔은 마지막 행의 이름, 인덱스는 같은 행의 ID를 사용했다. HTTP·Redis·SQL 파싱 및 파일 열기 비용은 측정에서 제외했다.

각 조건을 빌드 교대로 별도 JVM에서 3회 실행했다. 표는 실행별 처리량과 최근접 순위 p95를 각각 구한 뒤 중앙값을 취한 결과다. 워커당 스캔은 워밍업 20회·측정 80회, 인덱스는 워밍업 10,000회·측정 50,000회다.

| 조회 | 워커 | 모니터 처리량 (req/s) | ReadWriteLock 처리량 (req/s) | 모니터 p95 (ms) | ReadWriteLock p95 (ms) |
|---|---:|---:|---:|---:|---:|
| 전체 스캔 | 1 | 26.52 | 26.86 | 40.3862 | 40.7011 |
| 전체 스캔 | 8 | 25.95 | 94.55 | 98.9353 | 127.8202 |
| ID 인덱스 | 1 | 414,897.88 | 404,027.48 | 0.0034 | 0.0036 |
| ID 인덱스 | 8 | 690,235.82 | 636,901.64 | 0.0030 | 0.0543 |

8워커 스캔 처리량은 약 3.64배였고, p95 중앙값은 98.94ms에서 127.82ms로 늘었다. 스캔 p95의 실행 간 범위는 모니터 53.97–2335.56ms, ReadWriteLock 87.93–149.55ms였다. 8워커 인덱스 조회에서는 처리량이 약 7.7% 감소했고 p95가 3.0µs에서 54.3µs로 늘었다. 공정 락 선택은 대기 중인 쓰기에 새 읽기가 계속 앞서는 일을 막는다. 이 SELECT 전용 실측으로 쓰기 지연이나 공정성 정책별 성능까지 비교하지는 않았다.

## 재현

`mini-dbms/README.md`에 따라 두 브랜치를 빌드한다. [DBMS-04의 데이터 생성 규칙](dbms-04-verification.md#재현)에서 처음 30,000행을 별도의 벤치마크 디렉터리에 준비한다. 다음 명령은 현재 브랜치의 `mini-dbms`에서 실행한다.

```bash
cp_dbms='target/classes:target/dependency/*'
"$JAVA_HOME/bin/java" -Xms512m -Xmx512m -cp "$cp_dbms" \
  minidbms.benchmark.ConcurrentQueryBenchmark /path/to/bench-data 30000 scan 8 20 80
"$JAVA_HOME/bin/java" -Xms512m -Xmx512m -cp "$cp_dbms" \
  minidbms.benchmark.ConcurrentQueryBenchmark /path/to/bench-data 30000 index 8 10000 50000
```

단일 워커는 8을 1로 바꾼다. 공통 모니터 빌드는 같은 진입점을 그대로 사용하고 기준 커밋의 `mini-dbms/target/classes`를 클래스 경로의 맨 앞에 둔다.
