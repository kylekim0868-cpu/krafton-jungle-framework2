package com.minidbms.dbms;

/**
 * 사용자에게 그대로 보여 줄 수 있는 DB 오류 (문법 오류, 없는 테이블, 타입 불일치 등).
 * CLI 는 "ERROR: 메시지" 로 출력하고, API 서버는 HTTP 400 으로 응답한다.
 */
public class DbException extends RuntimeException {

    public DbException(String message) {
        super(message);
    }

    public DbException(String message, Throwable cause) {
        super(message, cause);
    }
}
