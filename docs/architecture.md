# 공통 프로젝트 구조

같은 저장소 안에서 Mini Redis와 Mini DBMS를 **별도 Maven 프로젝트**로 관리한다. 루트 부모 POM, 상호 소스 의존성, 공유 데이터 파일은 없다.

| 경계 | Mini Redis | Mini DBMS |
|---|---|---|
| 소스·테스트·빌드 | `mini-redis/` | `mini-dbms/` |
| 문서·벤치마크 기록 | `docs/mini-redis/` | `docs/mini-dbms/` |
| 런타임 데이터 예시 | `mini-redis/data/store.aof` | `mini-dbms/data/products.jsonl` |
| 서버 포트 예시 | 8080 (상품 예제 8081) | 8090 |
| 모듈 설계 | [Redis 아키텍처](mini-redis/architecture.md) | [DBMS 아키텍처](mini-dbms/architecture.md) |

각 모듈의 Maven Wrapper·pom.xml·target·테스트 데이터를 분리한다. 테스트 서버는 임시 loopback 포트와 임시 디렉터리를 사용하고 테스트 종료 시 닫는다. 실행/테스트 명령은 [루트 README](../README.md#독립-테스트)와 각 모듈 README를 따른다.

## 현재 데이터 흐름

```text
클라이언트 → Redis HTTP → 해시 테이블 / 선택 AOF
클라이언트 → 상품 API → Redis 캐시 / 테스트용 원본

SQL 파일 → DBMS CLI ─┐
                     ├→ SQL 엔진 → JSONL 테이블 / B+ 트리
클라이언트 → DBMS HTTP┘
```

**상품 API와 DBMS는 아직 연결하지 않는다.** DBMS를 실제 원본으로 쓰는 어댑터·세 서버 통합 테스트는 별도 범위다. 두 프로젝트가 같은 폴더에 있다는 사실과 실제 런타임 연동은 구분한다.
