package minidbms;

import minidbms.http.QueryHttpServer;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.net.*;
import java.net.http.*;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(15)
class HttpStorageFailureTest {
    @TempDir Path directory;
    @Test void indexedReadFailurePoisonsLaterMissesAndWritesAndReturns503() throws Exception {
        var channel=new java.util.concurrent.atomic.AtomicReference<java.nio.channels.FileChannel>();
        var db=new Database(directory,(opened,bytes)->{ channel.set(opened); return opened.write(bytes); });
        try(var server=new QueryHttpServer(new InetSocketAddress("127.0.0.1",0),db);
            var client=HttpClient.newHttpClient()) {
            db.execute(new SqlStatement.Insert("persisted",1)); server.start();
            channel.get().close(); // injected storage channel failure, without rewriting data
            for(String sql:java.util.List.of("SELECT * FROM products WHERE id=1;", "SELECT * FROM products WHERE id=999;",
                    "INSERT INTO products(name,price) VALUES('blocked',1);")) {
                String body=tools.jackson.databind.json.JsonMapper.builder().build().writeValueAsString(java.util.Map.of("sql",sql));
                var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+server.port()+"/query"))
                        .timeout(Duration.ofSeconds(3)).header("Content-Type","application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body)).build();
                assertEquals(503,client.send(request,HttpResponse.BodyHandlers.ofString()).statusCode());
            }
            assertThrows(IllegalStateException.class,db::validateIndex);
        } finally { assertThrows(java.io.IOException.class,db::close); }
        try(var reopened=new Database(directory)) { assertEquals(1,reopened.validateIndex().entries()); }
    }

    @Test void partialFileWriteFails503AndLaterRequestsRemainBlocked() throws Exception {
        var fail=new AtomicBoolean();
        TableFile.Writer writer=(channel,bytes)->{
            if(fail.get()) { bytes.limit(bytes.position()+3); channel.write(bytes); throw new java.io.IOException("injected disk failure"); }
            return channel.write(bytes);
        };
        try(var db=new Database(directory,writer);
            var server=new QueryHttpServer(new InetSocketAddress("127.0.0.1",0),db);
            var client=HttpClient.newHttpClient()) {
            server.start(); fail.set(true);
            var insert=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+server.port()+"/query"))
                    .timeout(Duration.ofSeconds(3)).header("Content-Type","application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("{\"sql\":\"INSERT INTO products(name,price) VALUES('x',1);\"}")).build();
            var response=client.send(insert,HttpResponse.BodyHandlers.ofString());
            assertEquals(503,response.statusCode()); assertTrue(response.body().contains("STORAGE_UNAVAILABLE"));
            fail.set(false);
            assertEquals(503,client.send(insert,HttpResponse.BodyHandlers.ofString()).statusCode());
        }
        assertThrows(java.io.IOException.class,()->new Database(directory));
    }
}
