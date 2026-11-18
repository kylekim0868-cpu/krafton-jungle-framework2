package minidbms;

import java.util.List;

public sealed interface SqlStatement {
    record Insert(String name, long price) implements SqlStatement { }
    sealed interface Predicate permits Filter, IdRange {
        boolean matches(Product row);
    }

    record Filter(String column, Object value) implements Predicate {
        public boolean matches(Product row) {
            return switch (column) {
                case "id" -> value.equals(row.id());
                case "name" -> value.equals(row.name());
                case "price" -> value.equals(row.price());
                default -> throw new IllegalArgumentException("unknown column");
            };
        }
    }
    record IdRange(long low, long high) implements Predicate {
        @Override public boolean matches(Product row) {
            return low <= row.id() && row.id() <= high;
        }
    }

    record Select(List<String> columns, Predicate filter, int limit) implements SqlStatement {
        public boolean usesIdIndex() {
            return filter instanceof IdRange || filter instanceof Filter equal && equal.column().equals("id");
        }
        public Select { columns = List.copyOf(columns); }
    }
}
