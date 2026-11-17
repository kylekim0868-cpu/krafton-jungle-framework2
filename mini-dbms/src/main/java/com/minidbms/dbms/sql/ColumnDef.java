package com.minidbms.dbms.sql;

/** 컬럼 정의 한 개: 이름(소문자) + 타입. */
public record ColumnDef(String name, ColumnType type) {
}
