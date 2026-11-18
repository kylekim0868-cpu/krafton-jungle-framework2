package minidbms;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public final class SqlEngine {
    private final Database database;
    private final boolean useIndex;

    public SqlEngine(Database database) {
        this(database, true);
    }

    /** Allows repeatable index-on and index-off comparisons through the same SQL engine. */
    public SqlEngine(Database database, boolean useIndex) {
        this.database = java.util.Objects.requireNonNull(database);
        this.useIndex = useIndex;
    }

    public QueryResult executeOne(String sql, QueryLimits limits) throws IOException {
        return database.execute(parseOne(sql), limits, useIndex);
    }

    public static SqlStatement parseOne(String sql) {
        var statements = SqlParser.parseScript(sql);
        if (statements.size() != 1) throw new IllegalArgumentException("HTTP requires exactly one SQL statement");
        return statements.getFirst();
    }

    public List<QueryResult> execute(String sql) throws IOException {
        var statements = SqlParser.parseScript(sql); // parse all first; no writes for malformed scripts
        var results = new ArrayList<QueryResult>();
        for (var statement : statements) results.add(database.execute(statement, QueryLimits.UNLIMITED, useIndex));
        return List.copyOf(results);
    }
}
