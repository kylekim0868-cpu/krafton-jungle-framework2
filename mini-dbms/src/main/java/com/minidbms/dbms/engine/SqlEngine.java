package com.minidbms.dbms.engine;

import com.minidbms.dbms.DbException;
import com.minidbms.dbms.sql.ColumnType;
import com.minidbms.dbms.sql.Condition;
import com.minidbms.dbms.sql.Parser;
import com.minidbms.dbms.sql.Statement;
import com.minidbms.dbms.storage.Schema;

import java.util.ArrayList;
import java.util.List;

/**
 * SQL 처리기: SQL 문자열 → 파싱 → 실행 → 결과.
 *
 * <p>CLI, 벤치마크, API 서버가 모두 이 클래스 하나를 통해 DB 를 사용한다.
 * 상태를 거의 갖지 않으므로(설정값 두 개뿐) 여러 스레드가 동시에 호출해도 안전하다.
 * 실제 데이터 보호는 {@link Table} 의 읽기-쓰기 락이 담당한다.</p>
 *
 * <p><b>SELECT 의 접근 경로 선택</b> (WHERE 절의 id 조건을 보고 결정)</p>
 * <ol>
 *   <li>{@code id = 값} 이 있으면 → INDEX_POINT: B+ 트리로 한 행만 읽는다.</li>
 *   <li>{@code id >, >=, <, <=, BETWEEN} 이 있으면 → INDEX_RANGE: B+ 트리의 리프를 따라 범위만 읽는다.</li>
 *   <li>그 외 → FULL_SCAN: 파일 전체를 읽으며 조건을 검사한다.</li>
 * </ol>
 * 어느 경로든 읽어 온 행에 WHERE 조건 전체를 다시 검사하므로 결과는 항상 같고, 속도만 달라진다.
 */
public final class SqlEngine {

    private final Database database;
    /** false 로 바꾸면 항상 FULL_SCAN 한다 (인덱스 사용/미사용 성능 비교용) */
    private volatile boolean indexEnabled = true;
    /** SELECT 한 번이 돌려줄 수 있는 최대 행 수 (API 서버가 메모리 보호용으로 설정) */
    private volatile long maxResultRows = Long.MAX_VALUE;

    public SqlEngine(Database database) {
        this.database = database;
    }

    public Database database() {
        return database;
    }

    public void setIndexEnabled(boolean indexEnabled) {
        this.indexEnabled = indexEnabled;
    }

    public void setMaxResultRows(long maxResultRows) {
        this.maxResultRows = maxResultRows;
    }

    /** 세미콜론으로 구분된 여러 문장을 순서대로 실행한다. */
    public List<QueryResult> executeScript(String sql) {
        List<QueryResult> results = new ArrayList<>();
        for (Statement statement : Parser.parseScript(sql)) {
            results.add(execute(statement));
        }
        return results;
    }

    /** 문장 하나를 실행한다. */
    public QueryResult execute(String sql) {
        return execute(Parser.parseSingle(sql));
    }

    public QueryResult execute(Statement statement) {
        long start = System.nanoTime();
        QueryResult result;
        if (statement instanceof Statement.Select select) {
            result = executeSelect(select);
        } else if (statement instanceof Statement.Insert insert) {
            result = executeInsert(insert);
        } else {
            result = executeCreate((Statement.CreateTable) statement);
        }
        return result.withElapsedMs((System.nanoTime() - start) / 1_000_000.0);
    }

    // ------------------------------------------------------------------
    // CREATE TABLE
    // ------------------------------------------------------------------

    private QueryResult executeCreate(Statement.CreateTable create) {
        boolean created = database.createTable(create.table(), create.columns(), create.ifNotExists());
        String message = created
                ? "Table '" + create.table() + "' created"
                : "Table '" + create.table() + "' already exists, skipped";
        return new QueryResult("CREATE", List.of(), List.of(), 0, null, null, false, 0, message);
    }

    // ------------------------------------------------------------------
    // INSERT
    // ------------------------------------------------------------------

    private QueryResult executeInsert(Statement.Insert insert) {
        Table table = database.table(insert.table());
        Schema schema = table.schema();

        // targets[i] = VALUES 의 i 번째 값이 들어갈 컬럼 위치
        int[] targets;
        if (insert.columns() == null) {
            // 컬럼 목록 생략 → id 를 뺀 모든 컬럼을 스키마 순서대로
            targets = new int[schema.size() - 1];
            for (int i = 0; i < targets.length; i++) {
                targets[i] = i + 1;
            }
        } else {
            targets = new int[insert.columns().size()];
            boolean[] used = new boolean[schema.size()];
            for (int i = 0; i < targets.length; i++) {
                String name = insert.columns().get(i);
                if (name.equals(Schema.ID_COLUMN)) {
                    throw new DbException("Column 'id' is assigned automatically and cannot be inserted");
                }
                int idx = schema.indexOf(name);
                if (used[idx]) {
                    throw new DbException("Column '" + name + "' specified more than once");
                }
                used[idx] = true;
                targets[i] = idx;
            }
        }

        // 모든 행을 먼저 검증한다. 하나라도 잘못되면 아무것도 쓰지 않는다 (문장 단위 all-or-nothing).
        List<Object[]> rows = new ArrayList<>(insert.rows().size());
        for (List<Object> values : insert.rows()) {
            if (values.size() != targets.length) {
                throw new DbException("Expected " + targets.length + " values but got " + values.size());
            }
            Object[] row = new Object[schema.size()]; // 지정하지 않은 컬럼은 null
            for (int i = 0; i < targets.length; i++) {
                row[targets[i]] = coerceForInsert(values.get(i), schema, targets[i]);
            }
            rows.add(row);
        }

        long firstId = table.insert(rows);
        String message = rows.size() == 1
                ? "1 row inserted (id=" + firstId + ")"
                : rows.size() + " rows inserted (id=" + firstId + ".." + (firstId + rows.size() - 1) + ")";
        return new QueryResult("INSERT", List.of(), List.of(), rows.size(), firstId, null, false, 0, message);
    }

    /** INSERT 값이 컬럼 타입에 맞는지 검사하고, 저장할 Java 타입으로 맞춘다. */
    private static Object coerceForInsert(Object value, Schema schema, int columnIndex) {
        if (value == null) {
            return null;
        }
        ColumnType type = schema.column(columnIndex).type();
        switch (type) {
            case INT:
                if (value instanceof Long) {
                    return value;
                }
                break;
            case REAL:
                if (value instanceof Double) {
                    return value;
                }
                if (value instanceof Long l) {
                    return l.doubleValue(); // 정수 리터럴은 REAL 컬럼에 넣을 수 있다
                }
                break;
            default:
                if (value instanceof String) {
                    return value;
                }
        }
        throw new DbException("Type mismatch for column '" + schema.column(columnIndex).name()
                + "': expected " + type + " but got " + describe(value));
    }

    // ------------------------------------------------------------------
    // SELECT
    // ------------------------------------------------------------------

    /** 컬럼 위치가 확정된 조건 */
    private record BoundCondition(int column, Condition.Op op, Object value) {
    }

    private QueryResult executeSelect(Statement.Select select) {
        Table table = database.table(select.table());
        Schema schema = table.schema();

        // 1) 출력할 컬럼 결정
        int[] projection;
        List<String> columnNames;
        if (select.countStar()) {
            projection = null;
            columnNames = List.of("count");
        } else if (select.columns() == null) {
            projection = null;
            columnNames = schema.columnNames();
        } else {
            projection = new int[select.columns().size()];
            for (int i = 0; i < projection.length; i++) {
                projection[i] = schema.indexOf(select.columns().get(i));
            }
            columnNames = select.columns();
        }

        // 2) WHERE 조건의 컬럼 이름을 위치로 바꾸고 타입을 검사
        List<BoundCondition> conditions = new ArrayList<>();
        for (Condition c : select.where()) {
            int idx = schema.indexOf(c.column());
            checkComparable(schema, idx, c);
            conditions.add(new BoundCondition(idx, c.op(), c.value()));
        }

        // 3) id 조건을 분석해 접근 경로 결정
        Long pointId = null;
        long low = Long.MIN_VALUE;
        long high = Long.MAX_VALUE;
        boolean empty = false; // 조건만 봐도 결과가 없음을 알 수 있는 경우
        for (BoundCondition c : conditions) {
            if (c.column() != 0 || !(c.value() instanceof Long v)) {
                continue;
            }
            switch (c.op()) {
                case EQ:
                    if (pointId == null) {
                        pointId = v;
                    }
                    break;
                case GE:
                    low = Math.max(low, v);
                    break;
                case GT:
                    if (v == Long.MAX_VALUE) {
                        empty = true;
                    } else {
                        low = Math.max(low, v + 1);
                    }
                    break;
                case LE:
                    high = Math.min(high, v);
                    break;
                case LT:
                    if (v == Long.MIN_VALUE) {
                        empty = true;
                    } else {
                        high = Math.min(high, v - 1);
                    }
                    break;
                default:
                    break;
            }
        }
        boolean hasRange = low != Long.MIN_VALUE || high != Long.MAX_VALUE || empty;

        // 4) 행을 모으는 수집기 (조건 검사 → LIMIT → 컬럼 선택)
        long limit = select.limit() == null ? Long.MAX_VALUE : select.limit();
        long cap = maxResultRows;
        List<Object[]> out = new ArrayList<>();
        long[] matched = {0};
        boolean[] truncated = {false};
        boolean countOnly = select.countStar();

        Table.RowVisitor collector = row -> {
            for (BoundCondition c : conditions) {
                if (!matches(row[c.column()], c.op(), c.value())) {
                    return true; // 조건 불일치 → 다음 행으로
                }
            }
            if (countOnly) {
                matched[0]++;
                return true;
            }
            if (matched[0] >= limit) {
                return false;
            }
            if (matched[0] >= cap) {
                truncated[0] = true;
                return false;
            }
            matched[0]++;
            out.add(project(row, projection));
            return matched[0] < limit;
        };

        // 5) 접근 경로에 따라 실행
        String accessPath;
        if (countOnly && conditions.isEmpty()) {
            // 조건 없는 COUNT(*) 는 인덱스가 알고 있는 개수를 그대로 쓴다
            accessPath = QueryResult.METADATA;
            matched[0] = table.rowCount();
        } else if (!countOnly && limit == 0) {
            accessPath = QueryResult.METADATA;
        } else if (indexEnabled && pointId != null) {
            accessPath = QueryResult.INDEX_POINT;
            Object[] row = table.findById(pointId);
            if (row != null) {
                collector.visit(row);
            }
        } else if (indexEnabled && hasRange) {
            accessPath = QueryResult.INDEX_RANGE;
            if (!empty && low <= high) {
                table.scanIdRange(low, high, collector);
            }
        } else {
            accessPath = QueryResult.FULL_SCAN;
            table.scan(collector);
        }

        if (countOnly) {
            out.add(new Object[]{matched[0]});
        }
        String message = out.size() + (out.size() == 1 ? " row" : " rows");
        return new QueryResult("SELECT", columnNames, out, 0, null, accessPath, truncated[0], 0, message);
    }

    private static Object[] project(Object[] row, int[] projection) {
        if (projection == null) {
            return row;
        }
        Object[] result = new Object[projection.length];
        for (int i = 0; i < projection.length; i++) {
            result[i] = row[projection[i]];
        }
        return result;
    }

    /** 컬럼 타입과 리터럴 타입이 비교 가능한 조합인지 실행 전에 검사한다. */
    private static void checkComparable(Schema schema, int columnIndex, Condition c) {
        if (c.value() == null) {
            return; // IS NULL 계열이거나 "= NULL" (항상 거짓)
        }
        ColumnType type = schema.column(columnIndex).type();
        boolean columnIsText = type == ColumnType.TEXT;
        boolean valueIsText = c.value() instanceof String;
        if (columnIsText != valueIsText) {
            throw new DbException("Cannot compare column '" + c.column() + "' (" + type + ") with "
                    + describe(c.value()));
        }
    }

    /** SQL 의 3값 논리를 단순화: NULL 과의 비교는 IS NULL / IS NOT NULL 을 제외하고 항상 거짓 */
    static boolean matches(Object actual, Condition.Op op, Object expected) {
        if (op == Condition.Op.IS_NULL) {
            return actual == null;
        }
        if (op == Condition.Op.IS_NOT_NULL) {
            return actual != null;
        }
        if (actual == null || expected == null) {
            return false;
        }
        int cmp = compare(actual, expected);
        switch (op) {
            case EQ:
                return cmp == 0;
            case NE:
                return cmp != 0;
            case LT:
                return cmp < 0;
            case LE:
                return cmp <= 0;
            case GT:
                return cmp > 0;
            default:
                return cmp >= 0;
        }
    }

    private static int compare(Object a, Object b) {
        if (a instanceof String s) {
            return s.compareTo((String) b);
        }
        if (a instanceof Long x && b instanceof Long y) {
            return Long.compare(x, y);
        }
        // INT 와 REAL 이 섞이면 실수로 비교한다
        return Double.compare(((Number) a).doubleValue(), ((Number) b).doubleValue());
    }

    private static String describe(Object value) {
        if (value instanceof String s) {
            return "text '" + s + "'";
        }
        if (value instanceof Long) {
            return "integer " + value;
        }
        return "number " + value;
    }
}
