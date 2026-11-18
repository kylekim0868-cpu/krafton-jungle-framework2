package minidbms;

import tools.jackson.databind.json.JsonMapper;
import java.io.IOException;
import java.nio.file.*;

public final class Main {
    private Main() { }
    public static void main(String[] args) {
        int status = run(args);
        if (status != 0) System.exit(status);
    }

    static int run(String[] args) {
        if (args.length != 2) { error("Usage: minidbms.Main <data-directory> <sql-file>"); return 2; }
        try {
            String sql = Files.readString(Path.of(args[1]), java.nio.charset.StandardCharsets.UTF_8);
            SqlParser.parseScript(sql); // no data directory mutation for invalid SQL
            try (var database = new Database(Path.of(args[0]))) {
                var engine = new SqlEngine(database);
                var json = JsonMapper.builder().build();
                for (var result : engine.execute(sql)) {
                    System.out.writeBytes((json.writeValueAsString(result) + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    if (System.out.checkError()) throw new IOException("stdout write failed");
                }
            }
            return 0;
        } catch (IllegalArgumentException failure) {
            error("SQL_ERROR: " + failure.getMessage()); return 2;
        } catch (IOException failure) {
            error("STORAGE_ERROR: " + failure.getMessage()); return 3;
        }
    }
    private static void error(String text) {
        System.err.writeBytes((text + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
}
