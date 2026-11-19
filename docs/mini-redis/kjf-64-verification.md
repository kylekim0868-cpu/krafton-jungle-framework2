# KJF-64 — Redis 능동 만료와 대시보드

[KJF-64](https://kylekim0868.atlassian.net/browse/KJF-64) · [작업 브랜치](https://github.com/kylekim0868-cpu/krafton-jungle-framework2/tree/feat/KJF-64-ym-redis-dbms-integration)

`ym-mini-dbms`의 `6d5101d`에서 원격 브랜치를 먼저 게시하고 기준 SHA와 upstream을 확인했다. YM Redis의 TTL·AOF 이슈는 KJF-54·57이다. YJ `9a035a5`의 [능동 만료 방식](https://github.com/kylekim0868-cpu/krafton-jungle-framework2/blob/9a035a57fc35e4c31e8ba4631e3dab99c33468fd/mini-redis/src/main/java/com/miniredis/core/TTLManager.java#L47-L77)과 [대시보드 기능](https://github.com/kylekim0868-cpu/krafton-jungle-framework2/blob/9a035a57fc35e4c31e8ba4631e3dab99c33468fd/mini-redis/src/main/resources/static/index.html)을 YM 저장소와 HTTP 계약으로 구현했다.

- 스케줄러는 1초 간격으로 절대 만료 시각을 확인한다. 같은 저장소 락으로 만료 정리·조회·덮어쓰기를 보호한다. TTL 정리를 AOF에 따로 쓰지 않으며 로그 재생 시 절대 시각으로 만료를 재계산한다.
- `GET /dashboard`에서 `/keys/{key}`로 키를 조작하고 `GET /stats`로 충돌 분포·리사이즈·만료 횟수를 읽는다. 화면은 최대 80개 키와 32개 사용 버킷을 표시한다. 정리와 통계 수집에는 버킷 및 키 수에 비례하는 락 점유 비용이 든다.

## 확인 결과

- Java 25.0.5.1 / Maven 3.9.11의 `cd mini-redis && ./mvnw -B -o test`에서 **137개 통과**, 실패·오류·생략 0. 스케줄러가 조회한 적 없는 키를 제거하는지, 정리와 갱신이 겹쳐도 새 값이 남는지, 기존 AOF 강제 종료·복구가 동작하는지 확인했다.
- 실제 Mini Redis에 Chrome으로 연결한 뒤 390px 화면 및 데스크톱에서 가로 넘침을 확인했다. 한글 키를 저장·조회·삭제하고 목록 변화를 확인했다. HTML처럼 보이는 값은 화면에 텍스트로 표시됐다.
- 실제 DBMS와의 HTTP 연결 및 중단 복구 실험은 [DBMS 검증 기록](../mini-dbms/kjf-64-verification.md)에 있다.
