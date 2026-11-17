package com.minidbms.dbms.sql;

import com.minidbms.dbms.DbException;

import java.util.Locale;

/** 지원하는 컬럼 타입. Java 값으로는 INT=Long, REAL=Double, TEXT=String 으로 다룬다. */
public enum ColumnType {
    INT,
    REAL,
    TEXT;

    /** CREATE TABLE 에 적힌 타입 이름을 해석한다. 흔한 별칭도 받아 준다. */
    public static ColumnType parse(String name) {
        switch (name.toUpperCase(Locale.ROOT)) {
            case "INT":
            case "INTEGER":
            case "BIGINT":
                return INT;
            case "REAL":
            case "DOUBLE":
            case "FLOAT":
                return REAL;
            case "TEXT":
            case "VARCHAR":
            case "STRING":
                return TEXT;
            default:
                throw new DbException("Unknown column type: " + name + " (supported: INT, REAL, TEXT)");
        }
    }
}
