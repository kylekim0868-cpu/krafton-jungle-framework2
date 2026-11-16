# YM Mini DBMS

직접 구현한 SQL 처리기·파일 저장 → 메모리 B+ 트리 → HTTP API를 순차 구현한다. [요구사항](docs/requirements.md), [아키텍처](docs/architecture.md), [작업 지침](AGENTS.md), [팀 규칙](TEAM_DEVELOPMENT_RULES.md)을 따른다. 사용자 개인 통합 브랜치는 ym-mini-dbms, 에픽은 KJF-58이다.

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

- [DBMS-01](docs/dbms-01-verification.md): 파서/파일/새 JVM 조회, 39개 테스트.
- [DBMS-02](docs/dbms-02-verification.md): 직접 B+ 트리·재구축·100만행 실제 비교. 누적50개 테스트.
- DBMS-03 HTTP는 다음 단계다. 자동 PR 병합/Jira 완료 전환하지 않는다.
