package minidbms;

import java.io.IOException;
import java.nio.file.Path;
import java.util.*;

/** 단일 데이터 디렉터리의 products 테이블. 명령 하나가 공통 모니터 안에서 실행된다. */
public final class Database implements AutoCloseable {
    private final DirectoryLock ownership;
    private final TableFile table;
    private long nextId = 1;
    private boolean closed;
    private boolean writesFailed;

    public Database(Path directory) throws IOException {
        ownership = DirectoryLock.acquire(directory);
        TableFile opened = null;
        try {
            opened = new TableFile(directory.resolve("products.jsonl"));
            opened.scan((product, pointer) -> {
                if (product.id() != nextId) throw new IOException("nonsequential or duplicate row id");
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
        if (statement instanceof SqlStatement.Insert insert) return insert(insert);
        if (statement instanceof SqlStatement.Select select) return select(select);
        throw new IllegalArgumentException("unknown statement");
    }

    private QueryResult insert(SqlStatement.Insert insert) throws IOException {
        if (writesFailed) throw new IOException("writes disabled until database is reopened and validated");
        if (nextId == Long.MAX_VALUE) throw new IllegalArgumentException("ID space exhausted");
        var product = new Product(nextId, insert.name(), insert.price());
        try {
            table.append(product);
            nextId++;
        } catch (IOException failure) { writesFailed = true; throw failure; }
        return new QueryResult("INSERT", 1, product.id(), List.of());
    }

    private QueryResult select(SqlStatement.Select select) throws IOException {
        var rows = new ArrayList<Map<String, Object>>();
        table.scan((product, pointer) -> {
            if (select.filter() == null || select.filter().matches(product)) {
                var row = new LinkedHashMap<String, Object>();
                for (String column : select.columns()) row.put(column, switch (column) {
                    case "id" -> product.id(); case "name" -> product.name(); case "price" -> product.price();
                    default -> throw new IllegalArgumentException("unknown column");
                });
                rows.add(Collections.unmodifiableMap(row));
            }
            return rows.size() < select.limit();
        });
        return new QueryResult("SELECT", 0, null, rows);
    }

    @Override public synchronized void close() throws IOException {
        if (closed) return;
        closed = true;
        try { table.close(); } finally { ownership.close(); }
    }
}
