package com.minidbms.dbms.cli;

import com.minidbms.dbms.engine.QueryResult;

import java.io.PrintStream;
import java.util.List;
import java.util.Locale;

/**
 * 실행 결과를 터미널에 출력한다.
 *
 * <pre>
 *   +----+------+-----+
 *   | id | name | age |
 *   +----+------+-----+
 *   | 1  | kim  | 20  |
 *   +----+------+-----+
 *   1 row (0.213 ms, INDEX_POINT)
 * </pre>
 */
public final class TablePrinter {

    private TablePrinter() {
    }

    public static void print(QueryResult result, PrintStream out) {
        String time = String.format(Locale.ROOT, "%.3f ms", result.elapsedMs());

        if (!result.type().equals("SELECT")) {
            out.println(result.message() + " (" + time + ")");
            return;
        }

        List<String> columns = result.columns();
        List<Object[]> rows = result.rows();

        // 컬럼마다 가장 긴 값의 길이를 구한다
        int[] widths = new int[columns.size()];
        for (int i = 0; i < widths.length; i++) {
            widths[i] = columns.get(i).length();
        }
        String[][] cells = new String[rows.size()][columns.size()];
        for (int r = 0; r < rows.size(); r++) {
            for (int c = 0; c < widths.length; c++) {
                String text = format(rows.get(r)[c]);
                cells[r][c] = text;
                widths[c] = Math.max(widths[c], text.length());
            }
        }

        StringBuilder border = new StringBuilder("+");
        for (int width : widths) {
            border.append("-".repeat(width + 2)).append('+');
        }

        StringBuilder sb = new StringBuilder();
        sb.append(border).append('\n');
        appendRow(sb, columns.toArray(new String[0]), widths);
        sb.append(border).append('\n');
        for (String[] row : cells) {
            appendRow(sb, row, widths);
        }
        sb.append(border).append('\n');
        sb.append(result.message()).append(" (").append(time).append(", ").append(result.accessPath()).append(')');
        if (result.truncated()) {
            sb.append(" [truncated]");
        }
        out.println(sb);
    }

    private static void appendRow(StringBuilder sb, String[] cells, int[] widths) {
        sb.append('|');
        for (int i = 0; i < widths.length; i++) {
            sb.append(' ').append(cells[i]).append(" ".repeat(widths[i] - cells[i].length())).append(" |");
        }
        sb.append('\n');
    }

    /** 줄바꿈이 들어 있으면 표가 깨지므로 눈에 보이는 형태로 바꿔 출력한다. */
    private static String format(Object value) {
        if (value == null) {
            return "NULL";
        }
        return value.toString().replace("\r", "\\r").replace("\n", "\\n");
    }
}
