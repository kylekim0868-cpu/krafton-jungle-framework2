package com.minidbms.dbms.sql;

import com.minidbms.dbms.DbException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ParserTest {

    // ---------------- Tokenizer ----------------

    @Test
    @DisplayName("토크나이저: 키워드/기호/숫자/문자열을 구분한다")
    void tokenizeBasics() {
        List<Token> tokens = Tokenizer.tokenize("SELECT * FROM users WHERE age >= 20;");
        assertEquals(List.of("SELECT", "*", "FROM", "users", "WHERE", "age", ">=", "20", ";", ""),
                tokens.stream().map(Token::text).toList());
        assertEquals(Token.Type.EOF, tokens.get(tokens.size() - 1).type());
    }

    @Test
    @DisplayName("토크나이저: 문자열 안의 '' 는 작은따옴표 하나, 주석은 무시")
    void tokenizeStringEscapeAndComment() {
        List<Token> tokens = Tokenizer.tokenize("-- comment line\n'it''s; ok' -- tail");
        assertEquals(2, tokens.size());
        assertEquals(Token.Type.STRING, tokens.get(0).type());
        assertEquals("it's; ok", tokens.get(0).text());
    }

    @Test
    @DisplayName("토크나이저: 닫히지 않은 문자열과 알 수 없는 문자는 오류")
    void tokenizeErrors() {
        assertThrows(DbException.class, () -> Tokenizer.tokenize("SELECT 'abc"));
        assertThrows(DbException.class, () -> Tokenizer.tokenize("SELECT @ FROM t"));
    }

    // ---------------- SELECT ----------------

    @Test
    @DisplayName("SELECT *: 컬럼 목록 null, 조건 없음")
    void selectStar() {
        Statement.Select s = (Statement.Select) Parser.parseSingle("select * from Users");
        assertEquals("users", s.table()); // 이름은 소문자로 통일
        assertNull(s.columns());
        assertFalse(s.countStar());
        assertTrue(s.where().isEmpty());
        assertNull(s.limit());
    }

    @Test
    @DisplayName("SELECT 컬럼 + WHERE AND + LIMIT")
    void selectWithWhereAndLimit() {
        Statement.Select s = (Statement.Select) Parser.parseSingle(
                "SELECT id, name FROM users WHERE age > 20 AND name != 'kim' LIMIT 5;");
        assertEquals(List.of("id", "name"), s.columns());
        assertEquals(List.of(
                new Condition("age", Condition.Op.GT, 20L),
                new Condition("name", Condition.Op.NE, "kim")), s.where());
        assertEquals(5L, s.limit());
    }

    @Test
    @DisplayName("COUNT(*), IS NULL, BETWEEN, 음수/실수 리터럴")
    void selectVariants() {
        Statement.Select count = (Statement.Select) Parser.parseSingle("SELECT COUNT(*) FROM t WHERE x IS NOT NULL");
        assertTrue(count.countStar());
        assertEquals(List.of(new Condition("x", Condition.Op.IS_NOT_NULL, null)), count.where());

        Statement.Select between = (Statement.Select) Parser.parseSingle(
                "SELECT * FROM t WHERE id BETWEEN 10 AND 20 AND score <= -1.5");
        assertEquals(List.of(
                new Condition("id", Condition.Op.GE, 10L),
                new Condition("id", Condition.Op.LE, 20L),
                new Condition("score", Condition.Op.LE, -1.5)), between.where());
    }

    // ---------------- INSERT ----------------

    @Test
    @DisplayName("INSERT: 컬럼 목록 생략 / 지정, 여러 행, NULL")
    void insert() {
        Statement.Insert plain = (Statement.Insert) Parser.parseSingle("INSERT INTO users VALUES ('kim', 20)");
        assertNull(plain.columns());
        assertEquals(List.of(List.of("kim", 20L)), plain.rows());

        Statement.Insert multi = (Statement.Insert) Parser.parseSingle(
                "insert into users (name, age) values ('a', 1), ('b', NULL)");
        assertEquals(List.of("name", "age"), multi.columns());
        assertEquals(2, multi.rows().size());
        assertEquals(Arrays.asList("b", null), multi.rows().get(1));
    }

    // ---------------- CREATE ----------------

    @Test
    @DisplayName("CREATE TABLE: 타입 별칭과 IF NOT EXISTS")
    void createTable() {
        Statement.CreateTable c = (Statement.CreateTable) Parser.parseSingle(
                "CREATE TABLE IF NOT EXISTS users (name VARCHAR(50), age INTEGER, score DOUBLE)");
        assertTrue(c.ifNotExists());
        assertEquals(List.of(
                new ColumnDef("name", ColumnType.TEXT),
                new ColumnDef("age", ColumnType.INT),
                new ColumnDef("score", ColumnType.REAL)), c.columns());
    }

    // ---------------- 스크립트 / 오류 ----------------

    @Test
    @DisplayName("여러 문장: 빈 문장은 건너뛰고 마지막 세미콜론은 생략 가능")
    void script() {
        List<Statement> statements = Parser.parseScript(";;SELECT * FROM a; ; INSERT INTO a VALUES (1)");
        assertEquals(2, statements.size());
        assertTrue(Parser.parseScript("  -- only a comment\n").isEmpty());
    }

    @Test
    @DisplayName("문법 오류는 위치와 함께 DbException")
    void syntaxErrors() {
        assertThrows(DbException.class, () -> Parser.parseSingle("SELECT FROM users"));
        assertThrows(DbException.class, () -> Parser.parseSingle("SELECT * users"));
        assertThrows(DbException.class, () -> Parser.parseSingle("SELECT * FROM users WHERE"));
        assertThrows(DbException.class, () -> Parser.parseSingle("SELECT * FROM users LIMIT -1"));
        assertThrows(DbException.class, () -> Parser.parseSingle("INSERT INTO users VALUES ()"));
        assertThrows(DbException.class, () -> Parser.parseSingle("INSERT INTO users VALUES (1"));
        assertThrows(DbException.class, () -> Parser.parseSingle("DELETE FROM users"));
        assertThrows(DbException.class, () -> Parser.parseSingle("SELECT * FROM a SELECT * FROM b"));
        assertThrows(DbException.class, () -> Parser.parseSingle("SELECT * FROM select"));
        assertThrows(DbException.class, () -> Parser.parseSingle("SELECT * FROM t WHERE id = 99999999999999999999"));

        DbException e = assertThrows(DbException.class, () -> Parser.parseSingle("SELECT * FORM users"));
        assertTrue(e.getMessage().contains("position 9"), e.getMessage());
    }
}
