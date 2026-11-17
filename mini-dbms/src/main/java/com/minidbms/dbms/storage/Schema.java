package com.minidbms.dbms.storage;

import com.minidbms.dbms.DbException;
import com.minidbms.dbms.sql.ColumnDef;
import com.minidbms.dbms.sql.ColumnType;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 테이블 구조. 첫 번째 컬럼은 항상 자동 증가하는 {@code id INT} 이다.
 *
 * <p>파일 형식({@code <table>.schema}): 한 줄에 "컬럼이름 타입".</p>
 * <pre>
 *   id INT
 *   name TEXT
 *   age INT
 * </pre>
 */
public final class Schema {

    public static final String ID_COLUMN = "id";

    private final String tableName;
    private final List<ColumnDef> columns;
    private final Map<String, Integer> indexByName = new HashMap<>();

    /** @param columns id 를 포함한 전체 컬럼 (columns[0] 은 반드시 id INT) */
    public Schema(String tableName, List<ColumnDef> columns) {
        if (columns.isEmpty() || !columns.get(0).name().equals(ID_COLUMN)
                || columns.get(0).type() != ColumnType.INT) {
            throw new DbException("Schema of table '" + tableName + "' must start with 'id INT'");
        }
        this.tableName = tableName;
        this.columns = List.copyOf(columns);
        for (int i = 0; i < columns.size(); i++) {
            if (indexByName.put(columns.get(i).name(), i) != null) {
                throw new DbException("Duplicate column name: " + columns.get(i).name());
            }
        }
    }

    /** CREATE TABLE 에 적힌 사용자 컬럼 앞에 id 컬럼을 붙여 스키마를 만든다. */
    public static Schema withAutoId(String tableName, List<ColumnDef> userColumns) {
        if (userColumns.isEmpty()) {
            throw new DbException("A table needs at least one column");
        }
        List<ColumnDef> all = new ArrayList<>();
        all.add(new ColumnDef(ID_COLUMN, ColumnType.INT));
        for (ColumnDef c : userColumns) {
            if (c.name().equals(ID_COLUMN)) {
                throw new DbException("Column 'id' is created automatically; do not declare it");
            }
            all.add(c);
        }
        return new Schema(tableName, all);
    }

    public String tableName() {
        return tableName;
    }

    public List<ColumnDef> columns() {
        return columns;
    }

    public int size() {
        return columns.size();
    }

    public ColumnDef column(int index) {
        return columns.get(index);
    }

    /** @return 컬럼 위치. 없는 컬럼이면 예외 */
    public int indexOf(String columnName) {
        Integer idx = indexByName.get(columnName);
        if (idx == null) {
            throw new DbException("Unknown column '" + columnName + "' in table '" + tableName + "'");
        }
        return idx;
    }

    public List<String> columnNames() {
        List<String> names = new ArrayList<>(columns.size());
        for (ColumnDef c : columns) {
            names.add(c.name());
        }
        return names;
    }

    // ------------------------------------------------------------------
    // 파일 입출력
    // ------------------------------------------------------------------

    public void save(Path file) throws IOException {
        StringBuilder sb = new StringBuilder();
        for (ColumnDef c : columns) {
            sb.append(c.name()).append(' ').append(c.type().name()).append('\n');
        }
        Files.writeString(file, sb.toString(), StandardCharsets.UTF_8);
    }

    public static Schema load(String tableName, Path file) throws IOException {
        List<ColumnDef> columns = new ArrayList<>();
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            line = line.trim();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            String[] parts = line.split("\\s+");
            if (parts.length != 2) {
                throw new DbException("Invalid schema line in " + file.getFileName() + ": " + line);
            }
            columns.add(new ColumnDef(parts[0].toLowerCase(), ColumnType.parse(parts[1])));
        }
        return new Schema(tableName, columns);
    }
}
