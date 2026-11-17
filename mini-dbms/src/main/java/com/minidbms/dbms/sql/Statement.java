package com.minidbms.dbms.sql;

import java.util.List;

/**
 * 파싱 결과(AST). SQL 문장 하나가 아래 셋 중 하나의 객체로 바뀐다.
 * 실행기(SqlEngine)는 문자열이 아니라 이 객체만 보고 동작한다.
 */
public sealed interface Statement {

    /** CREATE TABLE [IF NOT EXISTS] t (name TEXT, age INT) */
    record CreateTable(String table, List<ColumnDef> columns, boolean ifNotExists) implements Statement {
    }

    /**
     * INSERT INTO t [(c1, c2)] VALUES (...), (...)
     *
     * @param columns 컬럼 목록을 생략했으면 null
     * @param rows    VALUES 뒤의 튜플들. 각 값은 Long / Double / String / null
     */
    record Insert(String table, List<String> columns, List<List<Object>> rows) implements Statement {
    }

    /**
     * SELECT * | c1, c2 | COUNT(*) FROM t [WHERE ...] [LIMIT n]
     *
     * @param columns   SELECT * 이면 null
     * @param countStar SELECT COUNT(*) 이면 true
     * @param where     AND 로 연결된 조건들 (없으면 빈 리스트)
     * @param limit     LIMIT 가 없으면 null
     */
    record Select(String table, List<String> columns, boolean countStar,
                  List<Condition> where, Long limit) implements Statement {
    }
}
