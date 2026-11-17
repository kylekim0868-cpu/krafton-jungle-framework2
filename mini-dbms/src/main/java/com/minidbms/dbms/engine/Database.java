package com.minidbms.dbms.engine;

import com.minidbms.dbms.DbException;
import com.minidbms.dbms.sql.ColumnDef;
import com.minidbms.dbms.storage.Schema;

import java.io.Closeable;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 데이터 디렉터리 하나 = 데이터베이스 하나.
 *
 * <pre>
 *   data/
 *     users.schema   ← 테이블 구조 (컬럼 이름과 타입)
 *     users.tbl      ← 실제 데이터 (한 줄 = 행 하나)
 * </pre>
 *
 * 시작할 때 디렉터리의 모든 {@code *.schema} 를 읽어 테이블을 열고 인덱스를 재구성한다.
 */
public final class Database implements Closeable {

    private static final String SCHEMA_SUFFIX = ".schema";
    private static final String DATA_SUFFIX = ".tbl";

    private final Path directory;
    private final Map<String, Table> tables = new ConcurrentHashMap<>();

    public Database(Path directory) {
        this.directory = directory;
        try {
            Files.createDirectories(directory);
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory, "*" + SCHEMA_SUFFIX)) {
                for (Path schemaFile : stream) {
                    String fileName = schemaFile.getFileName().toString();
                    String tableName = fileName.substring(0, fileName.length() - SCHEMA_SUFFIX.length());
                    Schema schema = Schema.load(tableName, schemaFile);
                    tables.put(tableName, new Table(directory.resolve(tableName + DATA_SUFFIX), schema));
                }
            }
        } catch (IOException e) {
            closeQuietly();
            throw new DbException("Failed to open database at " + directory + ": " + e.getMessage(), e);
        } catch (RuntimeException e) {
            closeQuietly();
            throw e;
        }
    }

    public Path directory() {
        return directory;
    }

    /** @return 테이블. 없으면 예외 */
    public Table table(String name) {
        Table table = tables.get(name);
        if (table == null) {
            throw new DbException("Table '" + name + "' does not exist");
        }
        return table;
    }

    public boolean hasTable(String name) {
        return tables.containsKey(name);
    }

    /** 테이블 이름 → 테이블 (이름순 정렬된 복사본) */
    public Map<String, Table> tables() {
        return new TreeMap<>(tables);
    }

    /**
     * 테이블 생성. 여러 스레드가 같은 이름으로 동시에 호출해도 하나만 성공하도록 synchronized 로 직렬화한다.
     * (테이블 이름은 파서가 영문자/숫자/밑줄만 허용하므로 경로 조작 문자가 들어올 수 없다.)
     *
     * @return 새로 만들었으면 true, 이미 있어서 건너뛰었으면 false (ifNotExists 일 때만)
     */
    public synchronized boolean createTable(String name, List<ColumnDef> userColumns, boolean ifNotExists) {
        if (tables.containsKey(name)) {
            if (ifNotExists) {
                return false;
            }
            throw new DbException("Table '" + name + "' already exists");
        }
        Schema schema = Schema.withAutoId(name, userColumns);
        Path schemaFile = directory.resolve(name + SCHEMA_SUFFIX);
        Path dataFile = directory.resolve(name + DATA_SUFFIX);
        try {
            // 스키마 없이 데이터 파일만 남아 있으면 구조를 알 수 없는 데이터를 새 테이블이 떠안게 된다
            if (Files.exists(dataFile) && Files.size(dataFile) > 0) {
                throw new DbException("Data file " + dataFile.getFileName()
                        + " already exists without a schema; remove it or restore " + schemaFile.getFileName());
            }
            Table table = new Table(dataFile, schema);
            schema.save(schemaFile);
            tables.put(name, table);
            return true;
        } catch (IOException e) {
            throw new DbException("Failed to create table '" + name + "': " + e.getMessage(), e);
        }
    }

    @Override
    public void close() {
        List<String> failures = new ArrayList<>();
        for (Map.Entry<String, Table> entry : tables.entrySet()) {
            try {
                entry.getValue().close();
            } catch (IOException e) {
                failures.add(entry.getKey() + ": " + e.getMessage());
            }
        }
        tables.clear();
        if (!failures.isEmpty()) {
            throw new DbException("Failed to close tables: " + failures);
        }
    }

    private void closeQuietly() {
        try {
            close();
        } catch (RuntimeException ignored) {
            // 여는 도중 실패한 경우라 닫기 오류는 무시한다
        }
    }
}
