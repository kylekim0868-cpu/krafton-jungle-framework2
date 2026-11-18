# DBMS-02 — 직접 구현한 B+ 트리와 100만행 실측

- 이슈 KJF-60 / 에픽 KJF-58, 브랜치 feat/KJF-60-ym-bplus-index.
- DBMS-01 검증 커밋8cd5fe2b113b936730df2e1aad3f067514537456에서 생성·구현 전 publish. upstream/원격 SHA 일치.
- Java25.0.5/Maven3.9.11 `cd mini-dbms && ./mvnw test`: **50개 통과**, 실패·오류·생략0.
- 독립 코드 리뷰 APPROVE / 아키텍처 CLEAR, 추가 quality gate 없음. 운영/테스트 -Xlint:all 및 diff 검사 통과.

## 구조와 검증

BPlusTree는 버킷/TreeMap을 감싼 것이 아니라 long 키 배열·leaf 값 배열·내부 자식 배열로 직접 구현했다. leaf/internal/root overflow를 각각 분할하며, 내부 구분 키는 오른쪽 자식의 최소 키다. leaf는 next로 연결한다. 중복키 덮어쓰기는 크기를 늘리지 않는다. 저장소 기본 maxKeys63은 단순한 fanout 선택이며 최적 성능 설정으로 주장하지 않는다.

- 용량3/4/7/63 각각1만키를 정순·역순·고정seed 무작위로 삽입하고 테스트용 TreeMap 오라클과 조회/overwrite/크기를 비교한다. 운영 코드에는 TreeMap이 없다.
- 구조 검사: 점유율·키 범위·구분키·동일leaf깊이·leaf연결·총항목 수. long 최소/최대값과 미스를 검증한다.
- 4000행을 전수 스캔한 결과와 모든 ID 인덱스 결과를 대조하고 재시작 재구축 후 반복한다. UTF8 다중바이트 포인터와 재시작 후 ID4001도 검증한다.
- ID 동등 조건만 PRIMARY_KEY_INDEX, name/price/조건 없는 검색은 TABLE_SCAN이며 QueryResult.accessPath로 구별한다.
- 리뷰에서 파일 쓰기 뒤 Pointer 할당 실패가 차단되지 않을 수 있음을 확인해 Pointer/버퍼를 쓰기 전 할당하도록 교정했다. I/O 전체 구간을 실패 상태로 보호하고 정상 완료 때만 해제한다. 실제3바이트 부분 쓰기 뒤 IOException/AssertionError를 주입해 읽기·쓰기·인덱스 진단을 모두 차단하고, 부분꼬리를 자동삭제하지 않고 재시작을 거절함을 검증한다.

## 실제 100만행 실험 — 2026-11-16

```sh
# Java25 선택, Maven compile 및 runtime dependency 복사 후 mini-dbms에서
BENCH_DIR=$(mktemp -d /private/tmp/dbms-million.XXXXXX)
"$JAVA_HOME/bin/java" -Xmx512m -cp 'target/classes:target/dependency/*' minidbms.benchmark.IndexBenchmark "$BENCH_DIR" 1000000 20 3
```

새 데이터 디렉터리를 요구하고 소유권 획득 후에도 빈 DB인지 재확인한다. 파일은 실험 후 보존하므로 따로 SELECT로 확인할 수 있다. 기존 데이터에는 덧붙이지 않는다. INSERT는 SQL 파싱을 제외한 Database API, 조회는 두 조건 모두 SQL 파싱/실행/파일읽기/결과 생성까지 포함한다.

환경: 25.0.5+8-LTS / Mac OS X 14.8.9 / aarch64, heap상한512MiB. 단일 스레드, 행수1000000, name=product-ID, price=ID%10000. 동일한20개 ID의 동일행·같은 전체컬럼을 조회하며 ID 조건과 name 조건 모두 LIMIT1이다. 같은키로 조건당3회 워밍업, 측정순서를쌍마다 교대하고 결과/접근경로를 검사한다. name은 첫 일치까지 선형탐색한다.

- 삽입+정상 close: 3.326초
- 재개+인덱스 재구축: 1.431초
- 파일 크기: 50666792바이트
- 인덱스: 높이4, leaf31250, 항목1000000
- 별도 JVM Main에서 id1000000 → name product-1000000/price0/PRIMARY_KEY_INDEX 확인.

| 조건 | p50 ms | p95 ms | 동작/초 |
|---|---:|---:|---:|
| ID 인덱스 | 0.054250 | 0.087125 | 17404.55 |
| name 선형탐색 | 248.570417 | 417.741209 | 4.64 |

[키·원시40개 지연과 통계 JSON](benchmarks/dbms02-million.json). nearest-rank p50/p95, 조건별 동작지연 합에 대한20/초당처리량을 독립 재계산해 일치 확인했다. 두 조건 사이의 대기/다른 조건 시간과 응답 검증 시간은 처리량 분모에 넣지 않는다.

## 한계

ID 대 name이라는 다른 필드·접근방식 비교다. 같은 쿼리의 실행계획 비교나 순수 B+트리 CPU 성능 측정이 아니다. 표본20/워밍업3의 짧은 단일장비 실험이므로 보편 배수/운영 최대 처리량으로 일반화하지 않는다. 모든 인덱스가 메모리에 상주하고 시작 시 파일 전수 재생 비용이 발생한다. 행 삭제가 없어 트리 삭제/병합은 구현하지 않았다. 정상close동기화·추가전용JSONL이라는 기존 내구성 한계는 유지한다.
