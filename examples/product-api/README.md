# 상품 API 캐시 예제 — REDIS-04

Java 25에서 저장소 루트 기준으로 빌드한다. 별도 Maven 프로젝트/의존성은 추가하지 않는다.

```sh
cd mini-redis
./mvnw test
./mvnw compile org.apache.maven.plugins:maven-dependency-plugin:3.11.0:copy-dependencies -DincludeScope=runtime
"$JAVA_HOME/bin/java" -cp 'target/classes:target/dependency/*' miniredis.Main 8080 100000
# 별도 터미널 (같은 mini-redis 디렉터리)
"$JAVA_HOME/bin/java" -cp 'target/classes:target/dependency/*' miniredis.product.ProductMain 8081 http://127.0.0.1:8080 30000 5
```

ProductMain 인자는 포트, 캐시 origin URL 또는 `none`, TTL(ms), 테스트 원본 지연(ms)이다. 초기 원본은 `{id:42,name:"사과"}` 하나이며 재시작 시 초기화된다. 실제 DBMS가 아니다. 두 서버 모두 loopback 예제다.

```sh
curl -i http://127.0.0.1:8081/products/42
curl -i http://127.0.0.1:8081/products/42
curl -s http://127.0.0.1:8081/stats
curl -i -X PUT http://127.0.0.1:8081/products/42 -H 'Content-Type: application/json' -d '{"name":"배"}'
curl -i http://127.0.0.1:8081/products/42
```

- `GET /products/{양의 int ID}`: 200과 상품 JSON, 원본에 없으면404. 첫 조회는 원본, 다음 유효 캐시 조회는 원본 호출 없음.
- `PUT /products/{id}`: JSON 문자열 `name` 하나, 기존 원본 변경 성공 시 DELETE로 캐시 무효화하고204. 없는 상품404. 검증 오류400, 미디어 타입415, 1MiB 초과413.
- `GET /stats`: 실험용 originReads/cacheFailures. 운영용 관리 API가 아니다.
- 캐시 장애/잘못된 응답/전체 응답500ms 초과는 원본 fallback. 캐시 저장 실패도 원본 결과를 반환한다. 원본 실패는503이며 성공으로 위장하지 않는다.
- 원본 변경 후 무효화 실패는 원본을 롤백하지 않는다. 기존 TTL이 오래된 값의 잔류를 제한한다. 진행 중인 조회의 stale refill·cache stampede를 해결하는 분산 버전/잠금은 없다.

별도 JVM 2개 검증 (위 빌드 후 저장소 루트):

```sh
python3 examples/product-api/smoke.py
```

실제 HTTP로 적중·TTL·삭제·변경 무효화와 캐시 SIGKILL 후 fallback을 검증한다. 자세한 결과는 [REDIS-04 검증 기록](../../docs/redis-04-verification.md)을 따른다.
