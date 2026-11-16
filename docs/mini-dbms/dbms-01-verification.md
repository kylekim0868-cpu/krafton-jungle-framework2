# DBMS-01 — SQL 처리기·파일 저장 검증

- 이슈 KJF-59 / 에픽 KJF-58, 브랜치 feat/KJF-59-ym-sql-file.
- 사용자 제공 origin/ym-mini-dbms c5381b3에서 별도 worktree 생성. 구현 전에 최초 publish, upstream 및 원격 SHA 일치 확인.
- Java25.0.5/Maven3.9.11 `cd mini-dbms && ./mvnw test`: 39개, 실패·오류·생략0.

## 검증 입력·기대 결과

- `INSERT INTO products(name,price) VALUES('사과🍎',1200);` → INSERT/affectedRows1/insertedId1. 다른 JVM `SELECT * FROM products WHERE id=1;` → 동일 name/price. 다시 INSERT는ID2.
- 재정렬 컬럼, 작은따옴표/세미콜론/주석처럼 생긴 문자열/실제 개행/빈 값/0원/한글·emoji 보존. JSON은 물리적 한 줄과 LF.
- SELECT 컬럼 선택, id/name/price 동등 조건, LIMIT, 미스 rows[] 검증.
- 잘못된 타입/음수/정수 overflow/중복·없는 컬럼/미지원문법/뒤의 문법 오류를 쓰기 전에 거절. 과대 행은 ID를 소모하지 않음.
- 잘린 행·중복ID·비순차ID·잘못된 JSON/스키마는 시작 실패하며 파일을 변경하지 않음.
- UTF8 바이트 포인터와 다중바이트 행 round-trip. 동일JVM/심볼릭링크/다른JVM 중복 소유 차단과 close 후 재개.
- 별도 JVM 테스트에서 OS 기본 stdout 인코딩에 따른 한글/emoji 손실을 재현해 CLI stdout/stderr를 명시적 UTF8 바이트로 출력하도록 수정했다.

## 완료 범위·한계

SQL 파서/실행기/고정 스키마/파일 저장/CLI와 실제 새 JVM 영속 조회까지다. B+ 트리와 HTTP는 후속 단계. 전체 스크립트 문법은 먼저 검사하지만 실행 중 I/O 실패의 트랜잭션 롤백은 없다. 정상 close에서 동기화하며 매 행 force/WAL/전원 장애 보장은 없다.

독립 리뷰에서 Jackson의 바이트 입력 자동 인코딩 감지로 UTF16 행이 허용되는 결함을 재현했다. UTF8 REPORT 디코더로 엄격 검증한 뒤 JSON 파싱하도록 수정하고 UTF16BE/LE·잘못된 UTF8 입력의 거절/파일 불변 회귀를 추가했다.

독립 코드 리뷰 APPROVE / 아키텍처 CLEAR. 사용자 지정 추가 code-review quality gate는 없음. 운영/테스트 소스 -Xlint:all 및 diff 검사 통과.
