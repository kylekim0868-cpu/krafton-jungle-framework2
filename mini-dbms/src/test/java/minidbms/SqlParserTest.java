package minidbms;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

class SqlParserTest {
    @Test void parsesCaseInsensitiveKeywordsReorderedColumnsQuotesAndComments() {
        var parsed = SqlParser.parseScript("-- input\n InSeRt INTO PRODUCTS (price,name) VALUES (1200,'O''Brien; -- 사과🍎'); SELECT name,id FROM products WHERE name='O''Brien; -- 사과🍎' LIMIT 2;");
        assertEquals(new SqlStatement.Insert("O'Brien; -- 사과🍎", 1200), parsed.getFirst());
        var select = (SqlStatement.Select) parsed.get(1);
        assertEquals(java.util.List.of("name", "id"), select.columns());
        assertTrue(select.filter().matches(new Product(1, "O'Brien; -- 사과🍎", 1200)));
        assertEquals(2, select.limit());
    }
    @Test void allowsEmptyNameZeroPriceAndFinalStatementWithoutSemicolon() {
        assertEquals(new SqlStatement.Insert("", 0), SqlParser.parseScript("INSERT INTO products(name,price) VALUES('',0)").getFirst());
        var select = (SqlStatement.Select) SqlParser.parseScript("SELECT * FROM products WHERE id=9223372036854775807").getFirst();
        assertEquals(Long.MAX_VALUE, ((SqlStatement.Filter) select.filter()).value());
    }
    @ParameterizedTest @ValueSource(strings = {"", "-- only comment", "CREATE TABLE products(id INT);", "DROP TABLE products;",
            "SELECT * FROM ../secret;", "SELECT other FROM products;", "SELECT id,id FROM products;", "SELECT * FROM other;",
            "SELECT * FROM products WHERE id='1';", "SELECT * FROM products WHERE name=1;", "SELECT * FROM products WHERE price=1.5;",
            "SELECT * FROM products WHERE id=1 OR id=2;", "SELECT * FROM products LIMIT 0;", "SELECT * FROM products LIMIT -1;",
            "SELECT * FROM products LIMIT 2147483648;", "INSERT INTO products VALUES ('a',1);", "INSERT INTO products(id,name) VALUES(1,'a');",
            "INSERT INTO products(name,name) VALUES('a','b');", "INSERT INTO products(name,price) VALUES('a',-1);",
            "INSERT INTO products(name,price) VALUES(1,'a');", "INSERT INTO products(name,price) VALUES('a',9223372036854775808);",
            "INSERT INTO products(name,price) VALUES('unclosed,1);", "INSERT INTO products(name,price) VALUES('a',1,2);",
            "SELECT * FROM products SELECT * FROM products;", "SELECT * FROM products;;", "SELECT * FROM products WHERE id > 1;"})
    void rejectsUnsupportedOrInvalidInput(String sql) { assertThrows(IllegalArgumentException.class, () -> SqlParser.parseScript(sql)); }
}
