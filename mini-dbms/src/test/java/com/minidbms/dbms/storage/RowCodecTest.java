package com.minidbms.dbms.storage;

import com.minidbms.dbms.DbException;
import com.minidbms.dbms.sql.ColumnDef;
import com.minidbms.dbms.sql.ColumnType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RowCodecTest {

    private final Schema schema = Schema.withAutoId("t", List.of(
            new ColumnDef("name", ColumnType.TEXT),
            new ColumnDef("score", ColumnType.REAL),
            new ColumnDef("memo", ColumnType.TEXT)));

    private void assertRoundTrip(Object... row) {
        String line = RowCodec.encode(row);
        assertFalse(line.contains("\n"), "encoded row must stay on one line");
        assertArrayEquals(row, RowCodec.decode(line, schema));
    }

    @Test
    @DisplayName("일반 값은 쉼표로 구분된 한 줄이 된다")
    void plainRow() {
        assertEquals("1,kim,3.5,hello", RowCodec.encode(new Object[]{1L, "kim", 3.5, "hello"}));
        assertRoundTrip(1L, "kim", 3.5, "hello");
    }

    @Test
    @DisplayName("쉼표/줄바꿈/역슬래시가 들어 있어도 원래 값으로 복원된다")
    void specialCharacters() {
        assertRoundTrip(2L, "Kim, Jr.", -0.25, "line1\nline2\r\n");
        assertRoundTrip(3L, "back\\slash", 1e300, "\\N");   // 글자 그대로의 "\N" 은 NULL 이 아니다
        assertRoundTrip(4L, ",,,", 0.0, "\\,\\n");
        assertRoundTrip(5L, "한글 🙂 émoji", 1.0, "'quote' \"double\"");
    }

    @Test
    @DisplayName("NULL 과 빈 문자열은 서로 다른 값으로 저장된다")
    void nullVersusEmpty() {
        assertRoundTrip(6L, null, null, "");
        assertRoundTrip(7L, "", null, null);
        assertEquals("8,\\N,\\N,", RowCodec.encode(new Object[]{8L, null, null, ""}));
    }

    @Test
    @DisplayName("id 만 빠르게 읽기")
    void decodeId() {
        assertEquals(123L, RowCodec.decodeId("123,kim,1.0,x"));
        assertThrows(DbException.class, () -> RowCodec.decodeId("abc,kim"));
    }

    @Test
    @DisplayName("깨진 줄은 DbException")
    void corruptLines() {
        assertThrows(DbException.class, () -> RowCodec.decode("1,kim", schema));              // 필드 부족
        assertThrows(DbException.class, () -> RowCodec.decode("1,kim,1.0,a,b", schema));      // 필드 초과
        assertThrows(DbException.class, () -> RowCodec.decode("x,kim,1.0,a", schema));        // id 가 숫자가 아님
        assertThrows(DbException.class, () -> RowCodec.decode("1,kim,1.0,bad\\", schema));    // 끝난 이스케이프
        assertThrows(DbException.class, () -> RowCodec.decode("1,kim,1.0,bad\\x", schema));   // 모르는 이스케이프
    }
}
