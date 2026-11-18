package minidbms;

import minidbms.index.BPlusTree;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;

/** 단일 데이터 디렉터리의 products 테이블. 명령 하나가 공통 모니터 안에서 실행된다. */
public final class Database implements AutoCloseable {
    private final DirectoryLock ownership;
    private final TableFile table;
    private final BPlusTree<TableFile.Pointer> primary = new BPlusTree<>(63);
    private long nextId = 1;
    private boolean closed;
    private boolean writesFailed; // 저장 과정에서 오류가 발생한 이후, 데이터 정합성이 확인되지 않은 상태로 추가 작업이 실행되는 것을 막기 위한 보호 장치

    public Database(Path directory) throws IOException { this(directory, java.nio.channels.FileChannel::write); }

    Database(Path directory, TableFile.Writer writer) throws IOException {
        ownership = DirectoryLock.acquire(directory);
        TableFile opened = null;
        try {
            // 전달받은 데이터 디렉터리에서 "products.jsonl"파일의 경로를 생성한다. 해당 경로와 파일 쓰기 기능(Writer)을 TableFile 생성자에 전달해 파일 기반 테이블 저장소를 초기화한다.
            opened = new TableFile(directory.resolve("products.jsonl"), writer);
            // 파일에 저장된 레코드를 순서대로 읽고 primary.put()은 각 레코드의 ID와 파일 위치를 B+Tree에 등록.
            opened.scan((product, pointer) -> {
                if (product.id() != nextId) throw new IOException("nonsequential or duplicate row id"); // 파일에 저장된 ID가 1부터 중복이나 누락 없이 순서대로 기록되어 있는지 검증하는 코드
                primary.put(product.id(), pointer);
                try { nextId = Math.incrementExact(nextId); }
                catch (ArithmeticException overflow) { throw new IOException("ID space exhausted", overflow); }
                return true;
            });
            table = opened;
        } catch (IOException | RuntimeException | Error failure) {
            if (opened != null) try { opened.close(); } catch (IOException closeFailure) { failure.addSuppressed(closeFailure); }
            try { ownership.close(); } catch (IOException closeFailure) { failure.addSuppressed(closeFailure); }
            throw failure;
        }
    }

    public QueryResult execute(SqlStatement statement) throws IOException { return execute(statement, QueryLimits.UNLIMITED); }

    public QueryResult execute(SqlStatement statement, QueryLimits limits) throws IOException {
        return execute(statement, limits, true);
    }

    synchronized QueryResult execute(SqlStatement statement, QueryLimits limits, boolean useIndex) throws IOException {
        Objects.requireNonNull(limits, "limits");
        requireAvailable();
        try {
            if (statement instanceof SqlStatement.Insert insert) return insert(insert);
            if (statement instanceof SqlStatement.Select select) return select(select, limits, useIndex);
            throw new IllegalArgumentException("unknown statement");
        } catch (IOException failure) {
            // A failed SELECT must not allow apparently healthy index misses or later writes.
            writesFailed = true;
            throw failure;
        }
    }

    /** Captured under the same lock as INSERT, including the file and index update. */
    public synchronized long revision() throws IOException {
        requireAvailable();
        return nextId - 1;
    }

    private void requireAvailable() throws IOException {
        if (closed) throw new IllegalStateException("database closed");
        if (writesFailed || table.writesFailed()) throw new IOException("database unavailable until reopened and validated");
    }

    private QueryResult insert(SqlStatement.Insert insert) throws IOException {
        if (writesFailed) throw new IOException("writes disabled until database is reopened and validated");
        if (nextId == Long.MAX_VALUE) throw new IllegalArgumentException("ID space exhausted");
        var product = new Product(nextId, insert.name(), insert.price());
        boolean appended = false;
        boolean applied = false;
        try {
            var pointer = table.append(product);
            appended = true;
            primary.put(product.id(), pointer);
            nextId++;
            applied = true;
        } catch (IOException failure) {
            // 저장 과정에서 오류가 발생한 이후, 데이터 정합성이 확인되지 않은 상태로 추가 작업이 실행되는 것을 막기 위한 보호 장치
            // 정합성을 회복하려면 실제 파일과 인덱스의 상태를 검증하고 필요한 경우 롤백하거나 인덱스를 재구축하는 등의 복구 과정이 필요하다
            writesFailed = true;
            throw failure;
        } finally {
            // Never serve a file/index mismatch after an index allocation failure.
            if (appended && !applied) writesFailed = true;
        }
        return new QueryResult("INSERT", 1, product.id(), List.of(), "APPEND");
    }

    private QueryResult select(SqlStatement.Select select, QueryLimits limits, boolean useIndex) throws IOException {
        var rows = new ArrayList<Map<String, Object>>();
        long[] bytes = {0};
        if (useIndex && select.filter() instanceof SqlStatement.Filter filter && filter.column().equals("id")) {
            long id = (Long) filter.value();
            var pointer = primary.get(id);
            if (pointer.isPresent()) {
                var location = pointer.orElseThrow();
                collect(rows, readIndexed(id, location), location, select, limits, 0);
            }
            return new QueryResult("SELECT", 0, null, rows, "PRIMARY_KEY_INDEX");
        }
        if (useIndex && select.filter() instanceof SqlStatement.IdRange range) {
            primary.rangeScan(range.low(), range.high(), (id, pointer) -> {
                bytes[0] = collect(rows, readIndexed(id, pointer), pointer, select, limits, bytes[0]);
                return rows.size() < select.limit();
            });
            return new QueryResult("SELECT", 0, null, rows, "PRIMARY_KEY_RANGE");
        }
        table.scan((product, pointer) -> {
            if (select.filter() == null || select.filter().matches(product)) {
                bytes[0] = collect(rows, product, pointer, select, limits, bytes[0]);
            }
            // 필요한 개수만 조회하고 스캔을 멈추는 코드
            return rows.size() < select.limit();
        });
        return new QueryResult("SELECT", 0, null, rows, "TABLE_SCAN");
    }

    private Product readIndexed(long id, TableFile.Pointer pointer) throws IOException {
        var product = table.read(pointer);
        if (product.id() != id) throw new IOException("index/file ID mismatch");
        return product;
    }

    private static long collect(List<Map<String, Object>> rows, Product product, TableFile.Pointer pointer,
                                SqlStatement.Select select, QueryLimits limits, long usedBytes) {
        // Full stored row bytes are a conservative bound even for smaller projections.
        if (rows.size() >= limits.maxRows() || pointer.length() > limits.maxRowBytes() - usedBytes) {
            throw new ResultLimitException();
        }
        rows.add(project(product, select.columns()));
        return usedBytes + pointer.length();
    }

    private static Map<String, Object> project(Product product, List<String> columns) {
        var row = new LinkedHashMap<String, Object>();
        for (String column : columns) row.put(column, switch (column) {
            case "id" -> product.id(); case "name" -> product.name(); case "price" -> product.price();
            default -> throw new IllegalArgumentException("unknown column");
        });
        // 원본 Map을 복사하는 것이 아니라 수정 불가능한 뷰(View)를 생성한다.
        return Collections.unmodifiableMap(row);
    }

    public synchronized BPlusTree.Structure validateIndex() {
        if (closed || writesFailed || table.writesFailed()) throw new IllegalStateException("database unavailable");
        var structure = primary.validate();
        if (structure.entries() != nextId - 1) throw new IllegalStateException("index size mismatch");
        return structure;
    }

    @Override public synchronized void close() throws IOException {
        if (closed) return;
        closed = true;
        try { table.close(); } finally { ownership.close(); }
    }
}
