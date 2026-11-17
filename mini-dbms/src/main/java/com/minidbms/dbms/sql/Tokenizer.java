package com.minidbms.dbms.sql;

import com.minidbms.dbms.DbException;

import java.util.ArrayList;
import java.util.List;

/**
 * 1단계: SQL 문자열 → 토큰 목록.
 *
 * <pre>
 *   SELECT * FROM users WHERE id = 3;
 *   → [IDENT select] [SYMBOL *] [IDENT from] [IDENT users] [IDENT where]
 *     [IDENT id] [SYMBOL =] [NUMBER 3] [SYMBOL ;] [EOF]
 * </pre>
 *
 * 키워드와 식별자는 여기서 구분하지 않는다. 둘 다 IDENT 이고, 파서가 문맥으로 판단한다.
 */
public final class Tokenizer {

    private Tokenizer() {
    }

    public static List<Token> tokenize(String sql) {
        List<Token> tokens = new ArrayList<>();
        int i = 0;
        int n = sql.length();

        while (i < n) {
            char c = sql.charAt(i);

            // 공백
            if (Character.isWhitespace(c)) {
                i++;
                continue;
            }

            // 한 줄 주석: -- 부터 줄 끝까지
            if (c == '-' && i + 1 < n && sql.charAt(i + 1) == '-') {
                while (i < n && sql.charAt(i) != '\n') {
                    i++;
                }
                continue;
            }

            // 식별자 / 키워드
            if (Character.isLetter(c) || c == '_') {
                int start = i;
                while (i < n && (Character.isLetterOrDigit(sql.charAt(i)) || sql.charAt(i) == '_')) {
                    i++;
                }
                tokens.add(new Token(Token.Type.IDENT, sql.substring(start, i), start));
                continue;
            }

            // 숫자: 123 또는 123.45
            if (isDigit(c)) {
                int start = i;
                while (i < n && isDigit(sql.charAt(i))) {
                    i++;
                }
                if (i + 1 < n && sql.charAt(i) == '.' && isDigit(sql.charAt(i + 1))) {
                    i++;
                    while (i < n && isDigit(sql.charAt(i))) {
                        i++;
                    }
                }
                tokens.add(new Token(Token.Type.NUMBER, sql.substring(start, i), start));
                continue;
            }

            // 문자열: '...' 안에서 작은따옴표는 '' 로 표현한다 (SQL 표준)
            if (c == '\'') {
                int start = i;
                StringBuilder sb = new StringBuilder();
                i++;
                boolean closed = false;
                while (i < n) {
                    char ch = sql.charAt(i);
                    if (ch == '\'') {
                        if (i + 1 < n && sql.charAt(i + 1) == '\'') {
                            sb.append('\'');
                            i += 2;
                            continue;
                        }
                        closed = true;
                        i++;
                        break;
                    }
                    sb.append(ch);
                    i++;
                }
                if (!closed) {
                    throw new DbException("Syntax error at position " + start + ": unterminated string literal");
                }
                tokens.add(new Token(Token.Type.STRING, sb.toString(), start));
                continue;
            }

            // 두 글자 기호
            if (i + 1 < n) {
                String two = sql.substring(i, i + 2);
                if (two.equals("<=") || two.equals(">=") || two.equals("<>") || two.equals("!=")) {
                    tokens.add(new Token(Token.Type.SYMBOL, two, i));
                    i += 2;
                    continue;
                }
            }

            // 한 글자 기호
            if ("(),;*=<>-".indexOf(c) >= 0) {
                tokens.add(new Token(Token.Type.SYMBOL, String.valueOf(c), i));
                i++;
                continue;
            }

            throw new DbException("Syntax error at position " + i + ": unexpected character '" + c + "'");
        }

        tokens.add(new Token(Token.Type.EOF, "", n));
        return tokens;
    }

    /** ASCII 숫자만 인정한다 (Character.isDigit 은 전각 숫자 등도 true 라서 쓰지 않는다). */
    private static boolean isDigit(char c) {
        return c >= '0' && c <= '9';
    }
}
