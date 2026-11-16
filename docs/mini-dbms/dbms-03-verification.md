# DBMS-03 — HTTP와 동시성 검증

- 이슈 KJF-61 / 에픽 KJF-58, 브랜치 feat/KJF-61-ym-http-concurrency.
- DBMS-02 c638b326c24e5fdcf2e9c4eb77b509f1015c89dd에서 분기·구현 전 publish. upstream/실제 원격 SHA 일치.
- Java25.0.5/Maven3.9.11 mini-dbms의 `./mvnw test`: **75개 통과**, 실패·오류·생략0.

## 요청 처리와 저장소 책임

QueryHttpServer는 경로/메서드/미디어/본문/UTF8·JSON 검증과 응답만 담당한다. 단일SQL은 같은 SqlEngine을 사용하며 Database의 공통 모니터가 파일·ID·인덱스를 보호한다. 고정8개 요청 worker가 있지만 DB명령은 직렬화된다. 응답 직렬화·전송은 모니터 밖이므로 느린 클라이언트가 DB잠금을 보유하지 않는다. HttpMain이 서버/DB의 생명주기를 소유하고 서버·worker 종료 뒤 정상 DBclose(force)를 수행한다.

QueryLimits는 HTTP타입에 의존하지 않는 저장소 조회 예산이다. 결과 수집 중1000행과 전체 저장 행의 보수적 바이트 예산(4MiB-8192)을 확인하고 초과 시413이다. 조용히 결과를 자르거나 전체 테이블 결과를 무제한 메모리에 적재하지 않는다. 작은 projection도 전체 행 크기로 계산한다. CLI는 기존 무제한 예산/사용자 LIMIT를 유지한다.

## 검증 입력·기대 결과

- 실제 HttpClient POST/query INSERT→200/insertedId1/APPEND; SELECT id1→200/같은Unicode값/PRIMARY_KEY_INDEX, price조건→TABLE_SCAN, ID미스→200/rows[].
- 잘못된JSON/UTF8/빈본문/누락·null·타입·중복·추가필드/뒤JSON은400 INVALID_REQUEST. 잘못된SQL·여러SQL은400 INVALID_SQL이며 파일/인덱스 불변. 경로404/메서드405/Allow POST/미디어415/본문초과413.
- 1001행 전체 조회413, LIMIT1000은200/정확히1000행. 큰 이름6개로 바이트 예산 초과413, LIMIT1은정상이며 이후 인덱스 정상 유지.
- **16개 동시 클라이언트가512개 INSERT**하고 각 ID를 즉시 SELECT한다. ID1~512가 중복 없이 발급되고 이름과 일치한다. 전수 스캔/파일512행/인덱스 항목 수를 대조하고, 닫았다 재개해 모든 ID를 다시 읽으며 다음INSERT가ID513임을 확인한다.
- 실제 FileChannel 부분쓰기 IOException→HTTP503, 고장 플래그를 복구해도 후속쓰기503. 부분꼬리는 재시작 시 거절한다.
- 독립 리뷰에서 SELECT 읽기 I/O 오류가 후속명령을 차단하지 않는 계약결함을 확인해 Database 공통실행경계에서 IOException을 상태 고정/재전파하도록 수정했다. 실제 채널강제close 고장주입 후 ID1조회503→ID미스조회503→INSERT503, 소유권반환뒤 정상파일재개를 검증한다. 파일내용을 억지로 바꿔 오류를 숨기지 않는다.
- 요청 전송 오류와 DB오류를 별도 경계로 구분한다. rawTCP로 Content-Length100에 '{}'만 보내고 half-close한 뒤 DB의 SELECT/INSERT가 여전히 정상임을 확인한다. 저장소오류만503/ERROR, 요청본문실패는400/WARNING(연결유지가능시), 응답전송실패는WARNING/재전파다.
- **별도 HTTP JVM → 별도 CLI JVM → HTTP 재시작 → 강제 종료 → 재시작**에서 같은 파일/행/다음ID를 확인한다. 시험한 로컬 OS에서 프로세스 강제 종료 복구를 확인한 것이며 전원차단 보장으로 확대하지 않는다.
- 시작 전 close와 반복close는 안전하며 서버가 주입된 DB를 임의로 닫지 않는다. 포트/디렉터리 소유권과 실제 프로세스 종료를 확인한다.

## 최종 확인

독립 코드 리뷰 APPROVE / 아키텍처 CLEAR. 수정 후 두 lane이 전체75개를 직접 재실행해 통과했다. 운영 Java -Xlint:all -Werror, diff 검사 통과. 추가 blocking/advisory code-review quality gate 없음.

실제100만행 파일을 별도 HTTP JVM(heap512MiB)에서 다시 열어 id1000000 인덱스조회/동일name 선형조회가 같은 행인지 확인했다. 전체SELECT는413, 이후ID1조회는정상으로 결과 예산이 DB를 오염시키지 않음을 확인했다. 서버는 정확한 자식프로세스만 종료했다.

## 실행·제약

[README 실행 명령](../../mini-dbms/README.md#http-서버직접-확인). 서버는 loopback 교육용이다. 인증/TLS/부하차단/쿼리기한/멱등성 키/트랜잭션/WAL은 없다. 긴 스캔은 쓰기를 대기시키고 고정 worker 대기열은 무제한이다. 정상 INSERT라도 응답 유실 후 재시도하면 새 행이 생길 수 있다. 파일은 보존해야 하며, 정상close에서 force하되 매 INSERT fsync/전원장애 원자성을 보장하지 않는다. 테스트에 출력되는 주입 오류 로그는 의도된 진단이다.

최종 문서8개/상대링크·앵커21개 및 원시 통계 재계산 검증 통과. 기존 팀 문서의 Redis 전용 범위/옛 앵커를 DBMS 작업 문서에 맞게 정리했다. 최종 소스 기준 전체75개 테스트를 다시 실행해 실패·오류·생략0을 확인했다.
