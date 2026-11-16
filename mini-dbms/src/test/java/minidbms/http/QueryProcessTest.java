package minidbms.http;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;
import java.io.IOException;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(40)
class QueryProcessTest {
    @TempDir Path directory;
    private final JsonMapper json=JsonMapper.builder().build();
    @Test void independentJvmHttpCliAndRestartUseTheSameFileAndRebuiltIndex() throws Exception {
        Path data=directory.resolve("data");
        try(var client=HttpClient.newHttpClient()) {
            try(var first=start(data)) {
                var inserted=query(client,first.uri,"INSERT INTO products(name,price) VALUES('새 JVM 🍎',1200);");
                assertEquals(1,inserted.get("insertedId").longValue());
                assertEquals("새 JVM 🍎",query(client,first.uri,"SELECT * FROM products WHERE id=1;").get("rows").get(0).get("name").stringValue());
            }
            Path sql=Files.writeString(directory.resolve("read.sql"),"SELECT * FROM products WHERE id=1;");
            Path out=directory.resolve("cli.out"),err=directory.resolve("cli.err");
            var cli=launch("minidbms.Main",data.toString(),sql.toString()).redirectOutput(out.toFile()).redirectError(err.toFile()).start();
            try {
                assertTrue(cli.waitFor(8,TimeUnit.SECONDS)); assertEquals(0,cli.exitValue(),Files.readString(err));
                assertEquals("새 JVM 🍎",json.readTree(Files.readString(out)).get("rows").get(0).get("name").stringValue());
            } finally { if(cli.isAlive()) { cli.destroyForcibly(); cli.waitFor(5,TimeUnit.SECONDS); } }
            try(var second=start(data)) {
                assertEquals(2,query(client,second.uri,"INSERT INTO products(name,price) VALUES('second',2);").get("insertedId").longValue());
                second.process.destroyForcibly(); assertTrue(second.process.waitFor(5,TimeUnit.SECONDS));
            }
            try(var third=start(data)) {
                assertEquals(2,query(client,third.uri,"SELECT * FROM products;").get("rows").size());
                assertEquals("PRIMARY_KEY_INDEX",query(client,third.uri,"SELECT * FROM products WHERE id=2;").get("accessPath").stringValue());
                assertEquals(3,query(client,third.uri,"INSERT INTO products(name,price) VALUES('third',3);").get("insertedId").longValue());
            }
        }
    }
    private tools.jackson.databind.JsonNode query(HttpClient client,URI uri,String sql) throws Exception {
        String body=json.writeValueAsString(json.createObjectNode().put("sql",sql));
        var response=client.send(HttpRequest.newBuilder(uri.resolve("/query")).timeout(Duration.ofSeconds(5))
                .header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString(java.nio.charset.StandardCharsets.UTF_8));
        assertEquals(200,response.statusCode(),response.body()); return json.readTree(response.body());
    }
    private ProcessBuilder launch(String main,String... args) {
        var command=new java.util.ArrayList<String>();
        command.add(Path.of(System.getProperty("java.home"),"bin","java").toString());
        command.add("-cp"); command.add(System.getProperty("java.class.path")); command.add(main);
        command.addAll(java.util.List.of(args)); return new ProcessBuilder(command);
    }
    private Child start(Path data) throws Exception {
        Path out=Files.createTempFile(directory,"http-",".out"),err=Files.createTempFile(directory,"http-",".err");
        var process=launch("minidbms.http.HttpMain","0",data.toString()).redirectOutput(out.toFile()).redirectError(err.toFile()).start();
        try {
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(8);
            while(System.nanoTime()<deadline) {
                String text=Files.readString(out);
                if(text.startsWith("Mini DBMS listening on ") && text.endsWith("\n")) {
                    return new Child(process,URI.create(text.substring("Mini DBMS listening on ".length()).trim()));
                }
                if(!process.isAlive()) fail("child exited: "+Files.readString(err));
                Thread.sleep(20);
            }
            throw new AssertionError("HTTP child startup timed out");
        } catch(Exception | Error failure) { process.destroyForcibly(); process.waitFor(5,TimeUnit.SECONDS); throw failure; }
    }
    private static final class Child implements AutoCloseable {
        final Process process; final URI uri;
        Child(Process process,URI uri) { this.process=process; this.uri=uri; }
        @Override public void close() throws IOException {
            if(process.isAlive()) process.destroy();
            try {
                if(!process.waitFor(10,TimeUnit.SECONDS)) {
                    process.destroyForcibly(); if(!process.waitFor(5,TimeUnit.SECONDS)) throw new IOException("HTTP child remained alive");
                }
            } catch(InterruptedException failure) {
                process.destroyForcibly(); Thread.currentThread().interrupt(); throw new IOException("interrupted while closing child",failure);
            }
        }
    }
}
