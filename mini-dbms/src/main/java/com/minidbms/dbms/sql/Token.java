package com.minidbms.dbms.sql;

/**
 * SQL 문자열을 잘게 자른 조각 하나.
 *
 * @param type 조각의 종류
 * @param text 조각의 내용 (STRING 은 따옴표를 벗긴 실제 값)
 * @param pos  원본 SQL 에서의 시작 위치 (오류 메시지용)
 */
public record Token(Type type, String text, int pos) {

    public enum Type {
        /** 식별자 또는 키워드: select, users, name ... */
        IDENT,
        /** 숫자 리터럴: 42, 3.14 */
        NUMBER,
        /** 문자열 리터럴: 'kim' */
        STRING,
        /** 기호: ( ) , ; * = != <> < <= > >= - */
        SYMBOL,
        /** 입력의 끝 */
        EOF
    }

    @Override
    public String toString() {
        return type == Type.EOF ? "end of input" : "'" + text + "'";
    }
}
