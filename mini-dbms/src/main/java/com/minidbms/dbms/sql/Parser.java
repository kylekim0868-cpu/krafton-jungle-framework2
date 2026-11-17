package com.minidbms.dbms.sql;

import com.minidbms.dbms.DbException;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 2단계: 토큰 목록 → Statement(AST).
 *
 * <p>재귀 하강(recursive descent) 파서. 문법 규칙 하나가 메서드 하나에 대응한다.</p>
 *
 * <pre>
 *   script     := (statement? ';')* statement?
 *   statement  := select | insert | create
 *   select     := SELECT ('*' | COUNT '(' '*' ')' | ident (',' ident)*) FROM ident
 *                 [WHERE condition (AND condition)*] [LIMIT number]
 *   condition  := ident op literal | ident IS [NOT] NULL | ident BETWEEN literal AND literal
 *   insert     := INSERT INTO ident ['(' ident (',' ident)* ')'] VALUES tuple (',' tuple)*
 *   tuple      := '(' literal (',' literal)* ')'
 *   create     := CREATE TABLE [IF NOT EXISTS] ident '(' ident type (',' ident type)* ')'
 *   literal    := ['-'] number | string | NULL
 * </pre>
 */
public final class Parser {

    /** 테이블/컬럼 이름으로 쓸 수 없는 예약어 */
    private static final Set<String> RESERVED = Set.of(
            "select", "from", "where", "insert", "into", "values", "and", "limit",
            "create", "table", "null", "is", "not", "between", "if", "exists");

    private final List<Token> tokens;
    private int pos;

    private Parser(List<Token> tokens) {
        this.tokens = tokens;
    }

    /** 세미콜론으로 구분된 여러 문장을 파싱한다. 빈 문장(;;)은 건너뛴다. */
    public static List<Statement> parseScript(String sql) {
        Parser p = new Parser(Tokenizer.tokenize(sql));
        List<Statement> statements = new ArrayList<>();
        while (true) {
            while (p.acceptSymbol(";")) {
                // 빈 문장
            }
            if (p.peek().type() == Token.Type.EOF) {
                break;
            }
            statements.add(p.statement());
            if (p.peek().type() != Token.Type.EOF) {
                p.expectSymbol(";");
            }
        }
        return statements;
    }

    /** 문장이 정확히 하나여야 할 때 사용한다. */
    public static Statement parseSingle(String sql) {
        List<Statement> statements = parseScript(sql);
        if (statements.size() != 1) {
            throw new DbException("Expected exactly one SQL statement but found " + statements.size());
        }
        return statements.get(0);
    }

    // ------------------------------------------------------------------
    // 문장
    // ------------------------------------------------------------------

    private Statement statement() {
        if (isKeyword("select")) {
            return select();
        }
        if (isKeyword("insert")) {
            return insert();
        }
        if (isKeyword("create")) {
            return createTable();
        }
        throw error("SELECT, INSERT or CREATE");
    }

    private Statement.Select select() {
        expectKeyword("select");

        List<String> columns = null;
        boolean countStar = false;
        if (acceptSymbol("*")) {
            // SELECT * : columns == null
        } else if (isKeyword("count") && peekAhead(1).text().equals("(")) {
            pos++;
            expectSymbol("(");
            expectSymbol("*");
            expectSymbol(")");
            countStar = true;
        } else {
            columns = new ArrayList<>();
            do {
                columns.add(identifier("column name"));
            } while (acceptSymbol(","));
        }

        expectKeyword("from");
        String table = identifier("table name");

        List<Condition> where = new ArrayList<>();
        if (acceptKeyword("where")) {
            do {
                condition(where);
            } while (acceptKeyword("and"));
        }

        Long limit = null;
        if (acceptKeyword("limit")) {
            Token t = peek();
            if (t.type() != Token.Type.NUMBER || t.text().contains(".")) {
                throw error("a non-negative integer after LIMIT");
            }
            pos++;
            limit = parseLong(t);
        }

        return new Statement.Select(table, columns, countStar, where, limit);
    }

    /** 조건 하나를 읽어 out 에 추가한다. BETWEEN 은 조건 두 개(>=, <=)로 풀어서 넣는다. */
    private void condition(List<Condition> out) {
        String column = identifier("column name");

        if (acceptKeyword("is")) {
            boolean not = acceptKeyword("not");
            expectKeyword("null");
            out.add(new Condition(column, not ? Condition.Op.IS_NOT_NULL : Condition.Op.IS_NULL, null));
            return;
        }

        if (acceptKeyword("between")) {
            Object low = literal();
            expectKeyword("and");
            Object high = literal();
            out.add(new Condition(column, Condition.Op.GE, low));
            out.add(new Condition(column, Condition.Op.LE, high));
            return;
        }

        Token t = peek();
        Condition.Op op = null;
        if (t.type() == Token.Type.SYMBOL) {
            switch (t.text()) {
                case "=":
                    op = Condition.Op.EQ;
                    break;
                case "!=":
                case "<>":
                    op = Condition.Op.NE;
                    break;
                case "<":
                    op = Condition.Op.LT;
                    break;
                case "<=":
                    op = Condition.Op.LE;
                    break;
                case ">":
                    op = Condition.Op.GT;
                    break;
                case ">=":
                    op = Condition.Op.GE;
                    break;
                default:
                    break;
            }
        }
        if (op == null) {
            throw error("a comparison operator (=, !=, <, <=, >, >=, IS, BETWEEN)");
        }
        pos++;
        out.add(new Condition(column, op, literal()));
    }

    private Statement.Insert insert() {
        expectKeyword("insert");
        expectKeyword("into");
        String table = identifier("table name");

        List<String> columns = null;
        if (acceptSymbol("(")) {
            columns = new ArrayList<>();
            do {
                columns.add(identifier("column name"));
            } while (acceptSymbol(","));
            expectSymbol(")");
        }

        expectKeyword("values");
        List<List<Object>> rows = new ArrayList<>();
        do {
            expectSymbol("(");
            List<Object> row = new ArrayList<>();
            do {
                row.add(literal());
            } while (acceptSymbol(","));
            expectSymbol(")");
            rows.add(Collections.unmodifiableList(row));
        } while (acceptSymbol(","));

        return new Statement.Insert(table, columns, rows);
    }

    private Statement.CreateTable createTable() {
        expectKeyword("create");
        expectKeyword("table");

        boolean ifNotExists = false;
        if (acceptKeyword("if")) {
            expectKeyword("not");
            expectKeyword("exists");
            ifNotExists = true;
        }

        String table = identifier("table name");
        expectSymbol("(");
        List<ColumnDef> columns = new ArrayList<>();
        do {
            String name = identifier("column name");
            Token typeToken = peek();
            if (typeToken.type() != Token.Type.IDENT) {
                throw error("a column type (INT, REAL, TEXT)");
            }
            pos++;
            ColumnType type = ColumnType.parse(typeToken.text());
            // VARCHAR(255) 처럼 길이가 붙어도 받아 주되, 길이는 무시한다
            if (acceptSymbol("(")) {
                if (peek().type() != Token.Type.NUMBER) {
                    throw error("a length");
                }
                pos++;
                expectSymbol(")");
            }
            columns.add(new ColumnDef(name, type));
        } while (acceptSymbol(","));
        expectSymbol(")");

        return new Statement.CreateTable(table, columns, ifNotExists);
    }

    // ------------------------------------------------------------------
    // 리터럴 / 식별자
    // ------------------------------------------------------------------

    /** @return Long, Double, String 또는 null(NULL 리터럴) */
    private Object literal() {
        Token t = peek();

        if (t.type() == Token.Type.STRING) {
            pos++;
            return t.text();
        }
        if (t.type() == Token.Type.IDENT && t.text().equalsIgnoreCase("null")) {
            pos++;
            return null;
        }

        boolean negative = false;
        if (t.type() == Token.Type.SYMBOL && t.text().equals("-")) {
            negative = true;
            pos++;
            t = peek();
        }
        if (t.type() == Token.Type.NUMBER) {
            pos++;
            String text = negative ? "-" + t.text() : t.text();
            if (text.contains(".")) {
                return Double.parseDouble(text);
            }
            try {
                return Long.parseLong(text);
            } catch (NumberFormatException e) {
                throw new DbException("Syntax error at position " + t.pos() + ": integer out of range: " + text);
            }
        }
        throw error("a value (number, 'string' or NULL)");
    }

    private long parseLong(Token t) {
        try {
            return Long.parseLong(t.text());
        } catch (NumberFormatException e) {
            throw new DbException("Syntax error at position " + t.pos() + ": integer out of range: " + t.text());
        }
    }

    /** 테이블/컬럼 이름. 대소문자를 구분하지 않으므로 소문자로 통일한다. */
    private String identifier(String what) {
        Token t = peek();
        if (t.type() != Token.Type.IDENT || RESERVED.contains(t.text().toLowerCase(Locale.ROOT))) {
            throw error("a " + what);
        }
        pos++;
        return t.text().toLowerCase(Locale.ROOT);
    }

    // ------------------------------------------------------------------
    // 토큰 유틸
    // ------------------------------------------------------------------

    private Token peek() {
        return tokens.get(pos);
    }

    private Token peekAhead(int offset) {
        return tokens.get(Math.min(pos + offset, tokens.size() - 1));
    }

    private boolean isKeyword(String keyword) {
        Token t = peek();
        return t.type() == Token.Type.IDENT && t.text().equalsIgnoreCase(keyword);
    }

    private boolean acceptKeyword(String keyword) {
        if (isKeyword(keyword)) {
            pos++;
            return true;
        }
        return false;
    }

    private void expectKeyword(String keyword) {
        if (!acceptKeyword(keyword)) {
            throw error(keyword.toUpperCase(Locale.ROOT));
        }
    }

    private boolean acceptSymbol(String symbol) {
        Token t = peek();
        if (t.type() == Token.Type.SYMBOL && t.text().equals(symbol)) {
            pos++;
            return true;
        }
        return false;
    }

    private void expectSymbol(String symbol) {
        if (!acceptSymbol(symbol)) {
            throw error("'" + symbol + "'");
        }
    }

    private DbException error(String expected) {
        Token t = peek();
        return new DbException("Syntax error at position " + t.pos() + ": expected " + expected + " but found " + t);
    }
}
