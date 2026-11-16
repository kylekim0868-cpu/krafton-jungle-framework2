package minidbms;

import java.util.List;
import java.util.Map;

public record QueryResult(String command, long affectedRows, Long insertedId, List<Map<String, Object>> rows, String accessPath) {
    public QueryResult { rows = List.copyOf(rows); }
}
