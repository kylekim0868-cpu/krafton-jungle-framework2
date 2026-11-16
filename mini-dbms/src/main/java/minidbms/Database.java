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
    private boolean writesFailed;

    public Database(Path directory) throws IOException { this(directory, java.nio.channels.FileChannel::write); }

    Database(Path directory, TableFile.Writer writer) throws IOException {
        ownership = DirectoryLock.acquire(directory);
        TableFile opened = null;
        try {
            opened = new TableFile(directory.resolve("products.jsonl"), writer);
            opened.scan((product, pointer) -> {
                if (product.id() != nextId) throw new IOException("nonsequential or duplicate row id");
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

    public synchronized QueryResult execute(SqlStatement statement) throws IOException {
        if (closed) throw new IllegalStateException("database closed");
        if (writesFailed || table.writesFailed()) throw new IOException("database unavailable until reopened and validated");
        if (statement instanceof SqlStatement.Insert insert) return insert(insert);
        if (statement instanceof SqlStatement.Select select) return select(select);
        throw new IllegalArgumentException("unknown statement");
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
            writesFailed = true;
            throw failure;
        } finally {
            // Never serve a file/index mismatch after an index allocation failure.
            if (appended && !applied) writesFailed = true;
        }
        return new QueryResult("INSERT", 1, product.id(), List.of(), "APPEND");
    }

    private QueryResult select(SqlStatement.Select select) throws IOException {
        var rows = new ArrayList<Map<String, Object>>();
        if (select.filter() != null && select.filter().column().equals("id")) {
            long id = (Long) select.filter().value();
            var pointer = primary.get(id);
            if (pointer.isPresent()) {
                var product = table.read(pointer.orElseThrow());
                if (product.id() != id) throw new IOException("index/file ID mismatch");
                rows.add(project(product, select.columns()));
            }
            return new QueryResult("SELECT", 0, null, rows, "PRIMARY_KEY_INDEX");
        }
        table.scan((product, pointer) -> {
            if (select.filter() == null || select.filter().matches(product)) rows.add(project(product, select.columns()));
            return rows.size() < select.limit();
        });
        return new QueryResult("SELECT", 0, null, rows, "TABLE_SCAN");
    }

    private static Map<String, Object> project(Product product, List<String> columns) {
        var row = new LinkedHashMap<String, Object>();
        for (String column : columns) row.put(column, switch (column) {
            case "id" -> product.id(); case "name" -> product.name(); case "price" -> product.price();
            default -> throw new IllegalArgumentException("unknown column");
        });
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
