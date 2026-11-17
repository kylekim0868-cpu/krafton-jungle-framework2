package com.minidbms.dbms.storage;

import com.minidbms.dbms.DbException;
import com.minidbms.dbms.sql.ColumnType;

/**
 * 행(Object[]) ↔ 데이터 파일의 한 줄(String) 변환.
 *
 * <p>형식: 필드를 쉼표로 구분한 한 줄. 필드 안의 특수 문자는 역슬래시로 이스케이프한다.</p>
 * <pre>
 *   행: [3, "Kim, Jr.", null, 1.5]   →   줄: 3,Kim\, Jr.,\N,1.5
 * </pre>
 * <ul>
 *   <li>{@code \,} 쉼표, {@code \\} 역슬래시, {@code \n} 줄바꿈, {@code \r} 캐리지 리턴</li>
 *   <li>{@code \N} NULL (빈 문자열 ""과 구분된다)</li>
 * </ul>
 * 줄바꿈을 이스케이프하므로 "파일의 한 줄 = 행 하나"가 항상 성립한다.
 */
public final class RowCodec {

    private RowCodec() {
    }

    public static String encode(Object[] row) {
        StringBuilder sb = new StringBuilder(row.length * 12);
        for (int i = 0; i < row.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            Object v = row[i];
            if (v == null) {
                sb.append("\\N");
            } else if (v instanceof String s) {
                appendEscaped(sb, s);
            } else {
                sb.append(v); // Long, Double 은 특수 문자가 없다
            }
        }
        return sb.toString();
    }

    private static void appendEscaped(StringBuilder sb, String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\\':
                    sb.append("\\\\");
                    break;
                case ',':
                    sb.append("\\,");
                    break;
                case '\n':
                    sb.append("\\n");
                    break;
                case '\r':
                    sb.append("\\r");
                    break;
                default:
                    sb.append(c);
            }
        }
    }

    public static Object[] decode(String line, Schema schema) {
        int columnCount = schema.size();
        Object[] row = new Object[columnCount];
        StringBuilder field = new StringBuilder();
        boolean isNull = false;
        int col = 0;
        int n = line.length();

        for (int i = 0; i <= n; i++) {
            // i == n 은 마지막 필드를 마무리하기 위한 가상의 구분자
            if (i == n || line.charAt(i) == ',') {
                if (col >= columnCount) {
                    throw corrupt(schema, line);
                }
                row[col] = isNull ? null : convert(field.toString(), schema.column(col).type(), schema, line);
                col++;
                field.setLength(0);
                isNull = false;
                continue;
            }
            char c = line.charAt(i);
            if (c == '\\') {
                if (i + 1 >= n) {
                    throw corrupt(schema, line);
                }
                char next = line.charAt(++i);
                switch (next) {
                    case 'N':
                        isNull = true;
                        break;
                    case 'n':
                        field.append('\n');
                        break;
                    case 'r':
                        field.append('\r');
                        break;
                    case ',':
                    case '\\':
                        field.append(next);
                        break;
                    default:
                        throw corrupt(schema, line);
                }
            } else {
                field.append(c);
            }
        }

        if (col != columnCount) {
            throw corrupt(schema, line);
        }
        return row;
    }

    private static Object convert(String raw, ColumnType type, Schema schema, String line) {
        try {
            switch (type) {
                case INT:
                    return Long.parseLong(raw);
                case REAL:
                    return Double.parseDouble(raw);
                default:
                    return raw;
            }
        } catch (NumberFormatException e) {
            throw corrupt(schema, line);
        }
    }

    /** 줄 맨 앞의 id 만 빠르게 읽는다 (시작 시 인덱스 재구성용). */
    public static long decodeId(String line) {
        int comma = line.indexOf(',');
        try {
            return Long.parseLong(comma < 0 ? line : line.substring(0, comma));
        } catch (NumberFormatException e) {
            throw new DbException("Corrupt row (bad id): " + abbreviate(line));
        }
    }

    private static DbException corrupt(Schema schema, String line) {
        return new DbException("Corrupt row in table '" + schema.tableName() + "': " + abbreviate(line));
    }

    private static String abbreviate(String line) {
        return line.length() <= 80 ? line : line.substring(0, 80) + "...";
    }
}
