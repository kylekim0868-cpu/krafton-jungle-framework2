package minidbms;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;
import java.nio.file.*;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(20)
class CliProcessTest {
    @TempDir Path directory;
    record Run(int exit, String out, String error) { }
    private Run run(String sql) throws Exception {
        Path input=Files.createTempFile(directory,"query-",".sql"); Files.writeString(input,sql);
        Path stdout=Files.createTempFile(directory,"stdout-",".txt"), stderr=Files.createTempFile(directory,"stderr-",".txt");
        var process=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","java").toString(),"-cp",
                System.getProperty("java.class.path"),"minidbms.Main",directory.resolve("data").toString(),input.toString())
                .redirectOutput(stdout.toFile()).redirectError(stderr.toFile()).start();
        try { assertTrue(process.waitFor(8,TimeUnit.SECONDS)); return new Run(process.exitValue(),Files.readString(stdout),Files.readString(stderr)); }
        finally { if(process.isAlive()) { process.destroyForcibly(); process.waitFor(5,TimeUnit.SECONDS); } }
    }
    @Test void insertsInOneJvmAndSelectsInANewJvm() throws Exception {
        var inserted=run("INSERT INTO products(name,price) VALUES('사과🍎',1200);");
        assertEquals(0,inserted.exit(),inserted.error());
        var json=JsonMapper.builder().build(); assertEquals(1,json.readTree(inserted.out()).get("insertedId").longValue());
        var selected=run("SELECT * FROM products WHERE id=1;");
        assertEquals(0,selected.exit(),selected.error());
        assertEquals("사과🍎",json.readTree(selected.out()).get("rows").get(0).get("name").stringValue());
        assertEquals(2,json.readTree(run("INSERT INTO products(name,price) VALUES('배',900);").out()).get("insertedId").longValue());
    }
    @Test void invalidSqlFailsBeforeCreatingDataAndAValidMissIsEmpty() throws Exception {
        var invalid=run("CREATE TABLE products (id INT);");
        assertEquals(2,invalid.exit()); assertTrue(invalid.error().contains("SQL_ERROR"));
        assertFalse(Files.exists(directory.resolve("data")));
        var valid=run("SELECT * FROM products WHERE id=99;");
        assertEquals(0,valid.exit()); assertTrue(valid.out().contains("\"rows\":[]"));
    }
    @Test void otherJvmIsRejectedAfterDuplicateSameJvmOpenAndCanOpenAfterClose() throws Exception {
        Path data=directory.resolve("data");
        try (var db=new Database(data)) {
            assertThrows(java.io.IOException.class,()->new Database(data));
            var other=run("SELECT * FROM products;");
            assertEquals(3,other.exit()); assertTrue(other.error().contains("already owned"));
            db.execute(new SqlStatement.Insert("owner",1));
        }
        assertEquals(0,run("SELECT * FROM products;").exit());
    }
}
