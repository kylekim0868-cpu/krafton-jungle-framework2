package com.minidbms.dbms.sql;

/**
 * WHERE 절의 조건 하나: {@code column op value}.
 * 여러 조건은 AND 로만 연결된다.
 *
 * @param value 비교할 리터럴 (Long / Double / String / null). IS NULL 계열에서는 쓰지 않는다.
 */
public record Condition(String column, Op op, Object value) {

    public enum Op {
        EQ("="), NE("!="), LT("<"), LE("<="), GT(">"), GE(">="),
        IS_NULL("IS NULL"), IS_NOT_NULL("IS NOT NULL");

        private final String symbol;

        Op(String symbol) {
            this.symbol = symbol;
        }

        public String symbol() {
            return symbol;
        }
    }
}
