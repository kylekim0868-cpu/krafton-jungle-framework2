package minidbms;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public final class SqlEngine {
    private final Database database;
    public SqlEngine(Database database) { this.database = java.util.Objects.requireNonNull(database); }

    public List<QueryResult> execute(String sql) throws IOException {
        var statements = SqlParser.parseScript(sql); // parse all first; no writes for malformed scripts
        var results = new ArrayList<QueryResult>();
        for (var statement : statements) results.add(database.execute(statement));
        return List.copyOf(results);
    }
}
