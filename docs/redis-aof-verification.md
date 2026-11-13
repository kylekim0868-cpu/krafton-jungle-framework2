# REDIS-AOF — 동기 기록과 재시작 복구

- 이슈 [KJF-57](https://kylekim0868.atlassian.net/browse/KJF-57), 브랜치 `feat/KJF-57-ym-aof-recovery`.
- 검증된 REDIS-05 `9a1261c150c97fc617d78e002b1a708966ce198c`에서 생성·구현 전 최초 publish. upstream·원격 SHA 일치.
- Java25.0.5 / Maven3.9.11 `cd mini-redis && ./mvnw test`: **135개 통과, 실패·오류·생략0**. AOF 관련20개와 비용 실험1개 추가.
- 독립 코드 리뷰와 아키텍처 리뷰에서 발견한 OS 잠금 문제 수정·재검토 후 각각 APPROVE / CLEAR. 운영 Java 소스 `javac --release25 -Xlint:all -Werror` 검증.

## 저장 형식 — version1

파일은 프레임의 단순 연결이며 별도 전역 헤더는 없다. 각 프레임은 다음과 같다. 정수는 big-endian, CRC는 JDK CRC32C다.

| 오프셋 | 길이 | 내용 |
|---:|---:|---|
| 0 | 4 | magic `0x4D524131` / ASCII `MRA1` (형식 버전 포함) |
| 4 | 4 | UTF-8 JSON payload 바이트 수, 1~8388608 |
| 8 | 4 | payload 전체의 CRC32C |
| 12 | 4 | 앞12바이트 헤더의 CRC32C (length 손상도 검출) |
| 16 | 가변 | mutation JSON |

payload는 정확히 op/key/value/expiresAt 네 필드다. 예시:

```json
{"op":"PUT","key":"product:42","value":"상품 응답","expiresAt":1900000000000}
{"op":"DELETE","key":"product:42","value":null,"expiresAt":null}
```

각 줄은 서로 다른 payload 예시이며 실제 파일은 JSON Lines가 아닌 바이너리 프레임이다. PUT의 expiresAt=null은 영구 저장, 아니면 절대 epoch ms(long)다. 빈 키·값은 저장소 계약대로 유지한다. DELETE에는 value/expiresAt가 null이다. 직접 저장소의8MiB 레코드 한도는 직렬화 후 쓰기 전에 검사하며, HTTP 본문 한도1MiB는 별도로 유지한다. CRC는 우발적 손상 검출용이지 암호학적 무결성/인증 수단은 아니다.

## 쓰기·장애 계약

동일 저장소 잠금 안에서 입력 검증 → 전체 프레임 부분쓰기 반복 → FileChannel.force(true) → 메모리 반영 → HTTP204 순서다. DELETE는 없는 키에도 기록·동기화한다. 만료 정리만으로 발생하는 메모리 삭제는 파생 상태이므로 별도 기록하지 않고 절대 만료로 재계산한다.

기록/force 실패 시 메모리를 변경하지 않고 HTTP503 STORAGE_UNAVAILABLE를 반환한다. 이후 PUT/DELETE는 로그 쓰기를 시도하지 않고503, GET은 직전 메모리 상태(자연 만료 포함)를 읽는다. 기록은 완전히 써졌지만 동기화가 실패했을 수 있으므로 **실패 응답의 값이 재시작 후 나타날 수 있다**. 실패를 원자적 롤백이나 반드시 미기록으로 해석하지 않는다. 메모리 반영 중 예외/할당 Error도 이후 쓰기를 차단한다.

## 복구·파일 소유권

HTTP 시작 전에 모든 프레임을 검증하고 순서 재생한 뒤 최종 상태의 만료 항목을 제거한다. 재시작이 TTL을 다시 시작하지 않는다.

- 마지막16바이트 미만 헤더 또는 정상 헤더 뒤 짧은 payload만 마지막 정상 위치로 truncate하고 force(true). 이후 append 위치를 명시한다.
- 완전한 헤더의 magic/length/CRC 오류, 완전한 payload의 CRC/JSON/스키마 오류는 시작을 중단하며 파일을 자르지 않는다. 손상 파일을 자동 삭제·초기화하지 않는다.
- JVM registry가 실제 경로·fileKey·isSameFile로 **추가 채널 개방 전에** 중복을 거절하고, 프로세스 간에는 tryLock을 사용한다. open/close와 registry는 같은 전역 잠금 순서를 따른다.
- macOS에서는 동일 JVM에서 두 번째 채널을 열었다 닫는 것만으로 기존 POSIX 잠금이 풀릴 수 있다. 이 결함을 실제 재현해 수정했다. 회귀는 동일 경로/심볼릭 링크/하드 링크의 중복 거절 직후 다른 JVM도 계속 거절되고, 원래 소유자 종료 후 새 JVM이 시작됨을 확인한다.
- 신뢰된 로컬 파일 경로를 전제로 한다. 활성 파일을 외부 rename/unlink/교체·직접 쓰기하지 않고, 같은 JVM의 다른 코드에서 별도 채널을 열지 않는다. FileLock은 협력적 소유권이지 보안 경계가 아니다.

## 실행·복구 절차

Java25 선택과 runtime dependency 복사 후 mini-redis에서:

```sh
mkdir -p data
"$JAVA_HOME/bin/java" -cp 'target/classes:target/dependency/*' miniredis.Main 8080 100000 data/store.aof
```

세 번째 경로를 생략하면 메모리 모드다. 로그 부모 디렉터리는 미리 존재해야 한다. data는 Git에 포함하지 않는다. 실패 시 서버를 종료하고 보존된 로그의 백업을 확보한 뒤 같은 경로로 재시작해 검증한다. 잘린 꼬리만 자동 복구한다. 완전한 손상은 원인·백업을 확인하는 운영자 판단이 필요하며 자동 우회하지 않는다. 로그 압축·rewrite·복제·파일 시스템 장애 복구는 구현하지 않았다.

## 검증 입력·기대 결과

- Unicode/빈 값/충돌/리사이즈/덮어쓰기/삭제와 TTL 없는 덮어쓰기 재생. 시계1000+TTL10을 저장하고1010에 재시작하면 미스, 새 TTL100은1100까지 유지.
- 실제 FileChannel을3바이트씩 부분 쓰기해 전체 기록 뒤 force1회 확인. 부분 기록 후 IOException, force IOException, 무진행0쓰기 주입 → 메모리 보존/후속 쓰기 차단.
- 최종 레코드의 모든 바이트 절단 위치에서 마지막 정상 값만 복구하고 새 append 뒤 다시 재시작. 헤더·본문 완전 손상 및 CRC가 맞아도 잘못된 스키마/길이는 시작 실패·파일 보존.
- latch로 append 완료를 멈추면 HTTP 성공도 대기한다. 실패 HTTP503 뒤 GET은 이전 값, 이후 쓰기는 차단된다. 오류 주입 테스트의 ERROR 로그는 의도된 실패 진단이다.
- 실제 별도 JVM에 HTTP PUT/DELETE/TTL 성공 응답 후 destroyForcibly(시험 OS에서 SIGKILL), 중단 중 만료, 두 차례 재시작을 검증한다. 정상 값은 남고 삭제/만료 값은404, 재시작 후 새 PUT도 다음 강제 종료 후 보존된다.
- 병렬 쓰기 후 메모리 최종 상태와 재생 상태 일치. JVM·별칭·다른 프로세스 파일 소유권 회귀 포함.

## 매 쓰기 동기화 비용 — 2026-11-13 실측

```sh
"$JAVA_HOME/bin/java" -cp 'target/classes:target/dependency/*' miniredis.benchmark.AofBenchmark /private/tmp 100 500 512 3
```

동일 단일 스레드·워밍업100·신규 PUT500·512 ASCII문자·사전 할당으로 메모리/매 프레임 force(true)를3회 교대 측정했다. HTTP/상품 캐시 실험과 다른 **직접 저장소 PUT**이다. 전체 wall time에는 키 준비/루프 비용도 포함된다. 로그 크기352480바이트는 워밍업 포함600레코드다. 새 고유 임시 파일만 생성·종료 후 제거하며 기존 파일은 건드리지 않는다.

Mac OS X14.8.9 / aarch64 / APFS / Java25.0.5+8-LTS. [원시 지연3000개와 환경·통계](benchmarks/redis-aof.jsonl).

| 라운드 | 모드 | p50 ms | p95 ms | PUT/초 |
|---|---|---:|---:|---:|
| 1 | MEMORY | 0.000500 | 0.000625 | 651253.7 |
| 1 | AOF_FORCE_EACH | 4.017250 | 5.927875 | 213.2 |
| 2 | AOF_FORCE_EACH | 4.003459 | 5.810500 | 242.7 |
| 2 | MEMORY | 0.000292 | 0.000417 | 1417434.4 |
| 3 | MEMORY | 0.000250 | 0.000333 | 1687763.7 |
| 3 | AOF_FORCE_EACH | 4.014833 | 5.828750 | 240.8 |

이 장비의 매 쓰기 force 비용이 지배적이었다. 짧은 메모리 측정은 JIT/타이머/스케줄링 영향으로 변동이 크며 정밀한 배수·최대 처리량으로 해석하지 않는다. 다른 디스크/파일 시스템/부하에는 별도 측정이 필요하다.

## 보장 경계와 공식 근거

전체 AOF 프레임 기록 및 force(true) 성공 후 응답하며, 시험한 로컬 APFS에서 SIGKILL 후 복구를 검증했다. 이는 OS/디스크 캐시가 살아 있는 **프로세스 종료 검증**이다. 전원 차단·장치 고장·새 파일의 부모 디렉터리 항목 영속성까지 실증한 것은 아니다. 로그가 유실되면 복구할 수 없다.

- [JDK25 FileChannel.force/쓰기/절단](https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/nio/channels/FileChannel.html)
- [FileLock 수명과 플랫폼 제약](https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/nio/channels/FileLock.html)
- [CRC32C](https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/util/zip/CRC32C.html)
- [파일 fsync와 디렉터리 항목은 별도 — Linux 문서, macOS 보장으로 확대하지 않음](https://www.man7.org/linux/man-pages/man2/fsync.2.html)
