# DBMS-04 체크포인트 검증

[KJF-65](https://kylekim0868.atlassian.net/browse/KJF-65) · 기준 `ym-mini-dbms` / `45563daa8cc8a249b009fcf696251a2c3e481b7d`

## 정확성

- Java 25.0.5.1에서 `cd mini-dbms && ./mvnw -o test`를 실행했다. 기준 79개, 변경 후 90개가 통과했고 양쪽 모두 실패·오류·생략 0개다.
- 인덱스 복원 후 범위·단건 조회를 인덱스 비활성화 스캔과 대조했다. Unicode, 리프 분할 경계를 넘는 꼬리, 다음 ID, 미변경 체크포인트 재사용을 검증했다.
- 체크포인트 누락·잘림·체크섬·버전 오류, 같은 크기의 JSONL 교체 및 파일 축소를 확인했다. 잘린 행과 불연속 ID는 시작 단계에서 거절했다. 원자적 교체 실패 후에도 JSONL을 다시 읽었다.

- 별도 HTTP JVM에서 ID 2의 INSERT 성공을 확인한 직후 SIGKILL로 종료했다. 체크포인트가 그대로임을 확인하고 새 JVM에서 ID 1–2 범위 조회와 다음 INSERT의 ID 3을 검증했다.

## 재시작 시간

Apple M1 Pro 8코어 / 16GB, Java 25.0.5.1, 고정 힙 512MiB. 100만 행의 동일 JSONL(50,666,792바이트)에 모드별 워밍업 5회와 측정 15회를 적용했다. 같은 JVM에서 `new Database` 직전부터 열기 완료까지 측정하고, 매번 마지막 ID의 단건 결과와 revision을 확인했다. 체크포인트 템플릿 복사, 검증 조회, 종료는 측정에서 제외했다.

| 모드 | p50 (ms) | p95 (ms) |
|---|---:|---:|
| 기준 브랜치 전체 재구축 | 448.683 | 1368.080 |
| 100만 행 체크포인트 복원 | 86.771 | 158.245 |
| 99만 9천 행 체크포인트 복원 + 1천 행 증분 | 87.346 | 177.745 |

유효한 전체 체크포인트 크기는 4,000,088바이트다. 워밍업한 JVM과 OS 파일 캐시에서 측정한 값이다. 15개 표본의 최근접 순위 p95는 최댓값이다. JVM 시작, HTTP 기동, 정상 종료, 전원 장애 복구는 측정하지 않았다.

## 재현

`mini-dbms/README.md`에 따라 Maven 빌드와 런타임 의존성 복사를 수행한다. 각 모드에 전용 임시 데이터 디렉터리를 사용한다. 아래 Python으로 기준 파일과 999,000행 접두부를 만든다.

```bash
mkdir -p bench-data/{base,exact,tail}
python3 - <<'PY'
with open('bench-data/base/products.jsonl', 'w') as full, open('bench-data/tail/products.jsonl', 'w') as prefix:
    for row_id in range(1, 1_000_001):
        row = f'{{"id":{row_id},"name":"product-{row_id}","price":{row_id % 10000}}}\n'
        full.write(row)
        if row_id <= 999_000:
            prefix.write(row)
PY
cp bench-data/base/products.jsonl bench-data/exact/products.jsonl
printf '%s\n' 'SELECT id FROM products WHERE id = 1;' > bench-data/probe.sql
cp_dbms='target/classes:target/dependency/*'
for mode in exact tail; do
  "$JAVA_HOME/bin/java" -Xmx512m -cp "$cp_dbms" minidbms.Main "bench-data/$mode" bench-data/probe.sql
  mv "bench-data/$mode/products.index" "bench-data/$mode.index"
done
cp bench-data/base/products.jsonl bench-data/tail/products.jsonl
for mode in exact tail; do
  "$JAVA_HOME/bin/java" -Xms512m -Xmx512m -cp "$cp_dbms" \
    minidbms.benchmark.RecoveryBenchmark "bench-data/$mode" 1000000 5 15 "bench-data/$mode.index"
done
```

기준 실행에는 기준 커밋의 `mini-dbms/target/classes`를 클래스 경로의 맨 앞에 둔다. 그다음 새 브랜치의 `target/classes:target/dependency/*`를 추가해 같은 벤치마크 진입점을 사용한다. 인수는 `bench-data/base 1000000 5 15`이며 체크포인트 템플릿을 생략한다.
