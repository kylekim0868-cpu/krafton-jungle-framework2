# REDIS-04 — 상품 API 연동

- 이슈 [KJF-55](https://kylekim0868.atlassian.net/browse/KJF-55), 브랜치 `feat/KJF-55-ym-product-cache`.
- 검증된 REDIS-03 `6293dd96bd7fd223bb02c23abed048f5bc6df6c9`에서 생성·구현 전 최초 publish. upstream 및 원격 SHA 일치.
- Java25.0.5 / Maven3.9.11 `cd mini-redis && ./mvnw test`: **109개 통과, 실패·오류·생략0** (신규17개).
- 별도 JVM 2개 `JAVA_HOME=<JDK25> python3 examples/product-api/smoke.py`: **PASS**, 최종 원본 조회5회. 적중/TTL/삭제/변경 무효화/SIGKILL fallback 검증.

## 구조·입력·기대 결과

상품 HTTP → ProductService → HttpKeyCache(HTTP) → Mini Redis와 InMemoryProductOrigin을 분리했다. 원본은 테스트용 ConcurrentHashMap이며 Mini Redis의 직접 구현한 해시 테이블을 대체하지 않는다. DBMS는 구현하지 않았다.

- 처음 GET42는 원본1회·캐시 PUT TTL, 같은 요청의 두 번째 응답은 동일하고 원본1회 유지.
- 주입 시계로 1009 적중/1010 만료를 검증. 만료와 명시적 DELETE 후 조회마다 원본 호출1회 증가.
- 원본 PUT 성공 후 캐시 DELETE 및 다음 GET 새 이름. 없는 상품·검증 실패는 기존 원본/캐시 불변.
- 캐시 서버 중단 시 GET과 PUT이 원본에서 성공한다. 읽기/저장/무효화 실패는 cacheFailures 카운터로 관찰한다. 원본 실패는 IOException/HTTP503이다.
- 잘못된 캐시 상품 JSON은 원본 fallback 후 정상 값으로 교체. 없는 상품은 음성 캐시하지 않는다.
- 응답 헤더 뒤 본문이 멈춘 서버도 테스트. JDK25 request.timeout만 믿지 않고 sendAsync 전체 본문 future에 외부 deadline, 실패/timeout 시 취소, 최대 응답2MiB를 적용한다. 캐시 클라이언트 종료도3초 제한이다.

## 재현·제약

[예제 실행 안내](../examples/product-api/README.md). cache-aside는 원본 변경과 원자적이지 않다. 무효화 실패 또는 이미 진행 중인 조회의 stale refill은 TTL로 제한하며 분산 일관성을 보장하지 않는다. 캐시 미사용과 실제 DB 성능 비교는 아직 측정하지 않았다.

기술 근거: [JDK25 HttpClient 종료·취소 계약](https://docs.oracle.com/en/java/javase/25/docs/api/java.net.http/java/net/http/HttpClient.html), [BodyHandlers.limiting](https://docs.oracle.com/en/java/javase/25/docs/api/java.net.http/java/net/http/HttpResponse.BodyHandlers.html), [JDK26에서 본문까지 request timeout 확장](https://www.oracle.com/java/technologies/javase/26-relnote-issues.html). JDK25를 대상으로 별도 전체 응답 deadline을 선택했다.
