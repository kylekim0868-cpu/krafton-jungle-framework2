package minidbms;

import java.util.*;

/** SQL 토큰화·구문 분석을 직접 구현한다. parseScript는 실행 전에 전체 문법을 검증한다. */
public final class SqlParser {
    private enum Kind { WORD, STRING, NUMBER, SYMBOL, END }
    private record Token(Kind kind, String value, int offset) { }
    private final String sql;
    private int cursor;
    private Token token;

    private SqlParser(String sql) {
        this.sql = Objects.requireNonNull(sql, "sql");
        next();
    }

    public static List<SqlStatement> parseScript(String sql) {
        var parser = new SqlParser(sql);
        var statements = new ArrayList<SqlStatement>();
        while (parser.token.kind != Kind.END) {
            statements.add(parser.statement());
            if (!parser.take(";") && parser.token.kind != Kind.END) throw parser.error("expected ';'");
        }
        if (statements.isEmpty()) throw parser.error("SQL file is empty");
        return List.copyOf(statements);
    }

    private SqlStatement statement() {
        if (take("insert")) return insert();
        if (take("select")) return select();
        throw error("only INSERT and SELECT are supported");
    }

    private SqlStatement insert() {
        expect("into"); expect("products"); expect("(");
        var columns = columns(); expect(")"); expect("values"); expect("(");
        var values = new ArrayList<Object>(); values.add(literal());
        while (take(",")) values.add(literal());
        expect(")");
        if (columns.size() != 2 || !new HashSet<>(columns).equals(Set.of("name", "price")) || values.size() != 2) {
            throw error("INSERT requires name and price once each; id is generated");
        }
        Object name = values.get(columns.indexOf("name"));
        Object price = values.get(columns.indexOf("price"));
        if (!(name instanceof String text) || !(price instanceof Long amount) || amount < 0) {
            throw error("name must be text and price a nonnegative integer");
        }
        return new SqlStatement.Insert(text, amount);
    }

    private SqlStatement select() {
        List<String> projection = take("*") ? List.of("id", "name", "price") : columns();
        expect("from"); expect("products");
        SqlStatement.Filter filter = null;
        if (take("where")) {
            String column = column(); expect("="); Object value = literal();
            if ((column.equals("name") && !(value instanceof String))
                    || (!column.equals("name") && !(value instanceof Long))) throw error("predicate type mismatch");
            filter = new SqlStatement.Filter(column, value);
        }
        int limit = Integer.MAX_VALUE;
        if (take("limit")) {
            Object value = literal();
            if (!(value instanceof Long number) || number < 1 || number > Integer.MAX_VALUE) throw error("invalid LIMIT");
            limit = number.intValue();
        }
        return new SqlStatement.Select(projection, filter, limit);
    }

    private List<String> columns() {
        var columns = new ArrayList<String>(); columns.add(column());
        while (take(",")) columns.add(column());
        if (new HashSet<>(columns).size() != columns.size()) throw error("duplicate column");
        return columns;
    }

    private String column() {
        if (token.kind != Kind.WORD || !Set.of("id", "name", "price").contains(token.value)) throw error("unknown column");
        String column = token.value; next(); return column;
    }

    private Object literal() {
        if (token.kind == Kind.STRING) { String value = token.value; next(); return value; }
        if (token.kind == Kind.NUMBER) {
            String value = token.value;
            try { long number = Long.parseLong(value); next(); return number; }
            catch (NumberFormatException failure) { throw error("integer out of range"); }
        }
        throw error("expected quoted text or integer");
    }

    private boolean take(String value) {
        if ((token.kind == Kind.WORD || token.kind == Kind.SYMBOL) && token.value.equals(value)) { next(); return true; }
        return false;
    }
    private void expect(String value) { if (!take(value)) throw error("expected " + value); }
    private IllegalArgumentException error(String message) { return new IllegalArgumentException(message + " at offset " + token.offset); }

    private void next() {
        while (cursor < sql.length()) {
            if (Character.isWhitespace(sql.charAt(cursor))) { cursor++; continue; }
            if (sql.startsWith("--", cursor)) {
                while (cursor < sql.length() && sql.charAt(cursor) != '\n') cursor++;
                continue;
            }
            break;
        }
        int start = cursor;
        if (cursor == sql.length()) { token = new Token(Kind.END, "", cursor); return; }
        char c = sql.charAt(cursor++);
        if (c == '\'') {
            var text = new StringBuilder();
            while (cursor < sql.length()) {
                char ch = sql.charAt(cursor++);
                if (ch == '\'') {
                    if (cursor < sql.length() && sql.charAt(cursor) == '\'') { cursor++; text.append('\''); }
                    else { token = new Token(Kind.STRING, text.toString(), start); return; }
                } else text.append(ch);
            }
            throw new IllegalArgumentException("unterminated string at offset " + start);
        }
        if (isLetter(c) || c == '_') {
            while (cursor < sql.length() && (isLetter(sql.charAt(cursor)) || Character.isDigit(sql.charAt(cursor)) || sql.charAt(cursor) == '_')) cursor++;
            token = new Token(Kind.WORD, sql.substring(start, cursor).toLowerCase(Locale.ROOT), start); return;
        }
        if (Character.isDigit(c) || (c == '-' && cursor < sql.length() && Character.isDigit(sql.charAt(cursor)))) {
            while (cursor < sql.length() && Character.isDigit(sql.charAt(cursor))) cursor++;
            token = new Token(Kind.NUMBER, sql.substring(start, cursor), start); return;
        }
        if ("(),;=*".indexOf(c) >= 0) { token = new Token(Kind.SYMBOL, String.valueOf(c), start); return; }
        throw new IllegalArgumentException("unsupported character at offset " + start);
    }
    private static boolean isLetter(char c) { return c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z'; }
}
