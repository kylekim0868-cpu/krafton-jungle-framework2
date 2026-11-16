# 요구사항 안내

요구사항 계약은 모듈별 문서에서 독립적으로 관리한다. 이 문서는 분류와 연결만 제공한다.

| 모듈 | 요구사항 수 | 내용·완료 기준·이슈 연결 |
|---|---|---|
| Mini Redis | 필수 6개 + 선택 AOF 1개 = **7개** | [REDIS-00~REDIS-AOF](mini-redis/requirements.md) |
| Mini DBMS | 필수 **3개** | [DBMS-01~DBMS-03](mini-dbms/requirements.md) |
| 합계 | 필수 9개 + 선택 1개 = **10개** | 상세 테스트 조건은 각 요구사항의 완료 체크리스트 |

## 현재 범위와 구현 순서

두 모듈은 각각 구현된 독립 프로젝트다. 현재 작업은 동일 폴더 배치·문서 분리·개별 테스트 검증이며, 실제 Redis–DBMS 연동은 범위 밖이다. 현재 상품 API는 테스트용 원본을 사용한다. 폴더 통합만으로 원격 PR 병합이나 최종 완료 전환을 주장하지 않는다.

## 이슈·브랜치 연결

요구사항 하나 → Jira Task 하나 → 작업 브랜치 하나를 유지한다. Redis는 에픽 KJF-30 아래 [연결표](mini-redis/requirements.md#이슈브랜치-연결), DBMS는 에픽 KJF-58 아래 [연결표](mini-dbms/requirements.md#현재-범위와-구현-순서)를 따른다. 폴더 정리를 새로운 기능 요구사항으로 중복 등록하지 않는다.

[공통 개발 규칙](../TEAM_DEVELOPMENT_RULES.md) · [구조 안내](architecture.md)
