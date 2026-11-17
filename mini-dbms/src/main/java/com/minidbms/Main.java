package com.minidbms;

import com.minidbms.dbms.bench.Benchmark;
import com.minidbms.dbms.DbException;
import com.minidbms.dbms.cli.TablePrinter;
import com.minidbms.dbms.engine.Database;
import com.minidbms.dbms.engine.QueryResult;
import com.minidbms.dbms.engine.SqlEngine;
import com.minidbms.dbms.sql.Parser;
import com.minidbms.dbms.sql.Statement;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 프로그램 진입점. 첫 번째 인자로 실행 모드를 고른다.
 *
 * <pre>
 *   run &lt;file.sql&gt;   SQL 파일을 읽어 실행        (Phase 1)
 *   repl             대화형으로 SQL 입력          (Phase 1)
 *   bench            대량 삽입 + 인덱스 성능 비교  (Phase 2)
 * </pre>
 */
public final class Main {

    private static final String USAGE = String.join("\n",
            "Mini DBMS",
            "",
            "Usage:",
            "  run <file.sql> [--data-dir DIR]                      Execute SQL statements from a file",
            "  repl [--data-dir DIR]                                Interactive SQL shell",
            "  bench [--rows N] [--data-dir DIR]                    Insert N rows (default 1000000) and compare",
            "                                                       indexed vs. linear SELECT",
            "",
            "Defaults: --data-dir data (bench: bench-data)");

    private Main() {
    }

    public static void main(String[] args) {
        if (args.length == 0 || args[0].equals("--help") || args[0].equals("-h") || args[0].equals("help")) {
            System.out.println(USAGE);
            return;
        }
        try {
            System.exit(dispatch(args));
        } catch (DbException e) {
            System.err.println("ERROR: " + e.getMessage());
            System.exit(1);
        } catch (IllegalArgumentException e) {
            System.err.println("ERROR: " + e.getMessage());
            System.err.println();
            System.err.println(USAGE);
            System.exit(2);
        } catch (IOException e) {
            System.err.println("ERROR: " + e);
            System.exit(1);
        }
    }

    private static int dispatch(String[] args) throws IOException {
        String command = args[0];
        // "minidbms script.sql" 처럼 파일만 줘도 run 으로 처리한다
        if (command.toLowerCase().endsWith(".sql")) {
            return runFile(command, parseOptions(args, 1));
        }
        switch (command) {
            case "run":
                if (args.length < 2 || args[1].startsWith("--")) {
                    throw new IllegalArgumentException("run requires a SQL file path");
                }
                return runFile(args[1], parseOptions(args, 2));
            case "repl":
                return repl(parseOptions(args, 1));
            case "bench":
                return bench(parseOptions(args, 1));
            default:
                throw new IllegalArgumentException("Unknown command: " + command);
        }
    }

    // ------------------------------------------------------------------
    // run: SQL 파일 실행
    // ------------------------------------------------------------------

    private static int runFile(String file, Map<String, String> options) throws IOException {
        String sql;
        try {
            sql = Files.readString(Paths.get(file), StandardCharsets.UTF_8);
        } catch (NoSuchFileException e) {
            throw new DbException("SQL file not found: " + file);
        }
        // 실행 전에 파일 전체를 파싱한다 → 문법 오류가 있으면 한 문장도 실행하지 않는다
        List<Statement> statements = Parser.parseScript(sql);

        try (Database database = new Database(dataDir(options))) {
            SqlEngine engine = new SqlEngine(database);
            for (int i = 0; i < statements.size(); i++) {
                try {
                    TablePrinter.print(engine.execute(statements.get(i)), System.out);
                } catch (DbException e) {
                    System.out.flush();
                    System.err.println("ERROR in statement " + (i + 1) + ": " + e.getMessage());
                    return 1;
                }
            }
        }
        return 0;
    }

    // ------------------------------------------------------------------
    // repl: 대화형 셸
    // ------------------------------------------------------------------

    private static int repl(Map<String, String> options) throws IOException {
        try (Database database = new Database(dataDir(options))) {
            SqlEngine engine = new SqlEngine(database);
            BufferedReader reader = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
            System.out.println("Mini DBMS shell. End each statement with ';'. Type 'exit' to quit.");

            StringBuilder buffer = new StringBuilder();
            while (true) {
                System.out.print(buffer.length() == 0 ? "sql> " : "...> ");
                System.out.flush();
                String line = reader.readLine();
                if (line == null) {
                    break;
                }
                String trimmed = line.trim();
                if (buffer.length() == 0 && (trimmed.equalsIgnoreCase("exit") || trimmed.equalsIgnoreCase("quit")
                        || trimmed.equalsIgnoreCase("exit;") || trimmed.equalsIgnoreCase("quit;"))) {
                    break;
                }
                buffer.append(line).append('\n');
                if (!trimmed.endsWith(";")) {
                    continue; // 세미콜론이 나올 때까지 여러 줄을 모은다
                }
                try {
                    for (QueryResult result : engine.executeScript(buffer.toString())) {
                        TablePrinter.print(result, System.out);
                    }
                } catch (DbException e) {
                    System.out.println("ERROR: " + e.getMessage());
                }
                buffer.setLength(0);
            }
        }
        return 0;
    }

    // ------------------------------------------------------------------
    // bench
    // ------------------------------------------------------------------

    private static int bench(Map<String, String> options) throws IOException {
        long rows = intOption(options, "rows", 1_000_000, 1, 100_000_000);
        Path dir = Paths.get(options.getOrDefault("data-dir", "bench-data"));
        Benchmark.run(dir, rows, System.out);
        return 0;
    }

    // ------------------------------------------------------------------
    // 옵션 파싱
    // ------------------------------------------------------------------

    private static Path dataDir(Map<String, String> options) {
        return Paths.get(options.getOrDefault("data-dir", "data"));
    }

    /** "--name value" 쌍들을 읽는다. */
    private static Map<String, String> parseOptions(String[] args, int from) {
        Map<String, String> options = new HashMap<>();
        for (int i = from; i < args.length; i += 2) {
            if (!args[i].startsWith("--")) {
                throw new IllegalArgumentException("Unexpected argument: " + args[i]);
            }
            if (i + 1 >= args.length) {
                throw new IllegalArgumentException("Missing value for " + args[i]);
            }
            options.put(args[i].substring(2), args[i + 1]);
        }
        return options;
    }

    private static int intOption(Map<String, String> options, String name, int defaultValue, int min, int max) {
        String raw = options.get(name);
        if (raw == null) {
            return defaultValue;
        }
        try {
            int value = Integer.parseInt(raw);
            if (value < min || value > max) {
                throw new NumberFormatException();
            }
            return value;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("--" + name + " must be an integer between " + min + " and " + max);
        }
    }
}
