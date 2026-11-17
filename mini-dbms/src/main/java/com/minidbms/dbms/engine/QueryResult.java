package com.minidbms.dbms.engine;

import java.util.List;

/**
 * SQL 문장 하나를 실행한 결과. CLI 는 이것을 표로 출력하고, API 서버는 JSON 으로 바꾼다.
 *
 * @param type          "SELECT" / "INSERT" / "CREATE"
 * @param columns       SELECT 결과의 컬럼 이름 (그 외에는 빈 리스트)
 * @param rows          SELECT 결과 행 (그 외에는 빈 리스트)
 * @param affected      INSERT 로 추가된 행 수
 * @param firstInsertId INSERT 로 부여된 첫 id (INSERT 가 아니면 null). 마지막 id 는 firstInsertId + affected - 1
 * @param accessPath    SELECT 가 데이터를 찾은 방법: INDEX_POINT / INDEX_RANGE / FULL_SCAN / METADATA
 * @param truncated     결과가 최대 행 수 제한에 걸려 잘렸으면 true
 * @param elapsedMs     실행 시간(ms)
 * @param message       사람이 읽을 요약
 */
public record QueryResult(String type, List<String> columns, List<Object[]> rows, long affected,
                          Long firstInsertId, String accessPath, boolean truncated,
                          double elapsedMs, String message) {

    public static final String INDEX_POINT = "INDEX_POINT";
    public static final String INDEX_RANGE = "INDEX_RANGE";
    public static final String FULL_SCAN = "FULL_SCAN";
    public static final String METADATA = "METADATA";

    public QueryResult withElapsedMs(double ms) {
        return new QueryResult(type, columns, rows, affected, firstInsertId, accessPath, truncated, ms, message);
    }
}
