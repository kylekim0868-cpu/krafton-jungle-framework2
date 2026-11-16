package minidbms;

import java.util.List;

public sealed interface SqlStatement {
    record Insert(String name, long price) implements SqlStatement { }
    record Filter(String column, Object value) {
        public boolean matches(Product row) {
            return switch (column) {
                case "id" -> value.equals(row.id());
                case "name" -> value.equals(row.name());
                case "price" -> value.equals(row.price());
                default -> throw new IllegalArgumentException("unknown column");
            };
        }
    }
    record Select(List<String> columns, Filter filter, int limit) implements SqlStatement {
        public Select { columns = List.copyOf(columns); }
    }
}
