package minidbms.http;

import minidbms.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(45)
class QueryHttpTest {
    @TempDir Path directory;
    private final JsonMapper json=JsonMapper.builder().build();
    private Database database;
    private QueryHttpServer server;
    private HttpClient client;
    private URI base;
    @BeforeEach void start() throws Exception {
        database=new Database(directory);
        server=new QueryHttpServer(new InetSocketAddress("127.0.0.1",0),database); server.start();
        base=URI.create("http://127.0.0.1:"+server.port());
        client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    }
    @AfterEach void stop() throws Exception {
        try { if(client!=null) client.close(); }
        finally { try { if(server!=null) server.close(); } finally { if(database!=null) database.close(); } }
    }
    private HttpResponse<String> send(String path,String method,String body,String type) throws Exception {
        var builder=HttpRequest.newBuilder(base.resolve(path)).timeout(Duration.ofSeconds(5));
        if(type!=null) builder.header("Content-Type",type);
        return client.send(builder.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString(java.nio.charset.StandardCharsets.UTF_8));
    }
    private HttpResponse<String> sql(String sql) throws Exception {
        return send("/query","POST",json.writeValueAsString(json.createObjectNode().put("sql",sql)),"application/json");
    }
    private JsonNode success(String sql) throws Exception {
        var response=sql(sql); assertEquals(200,response.statusCode(),response.body()); return json.readTree(response.body());
    }
    @Test void actualHttpInsertSelectMissAndNonIndexedPredicateReuseTheEngine() throws Exception {
        var inserted=success("INSERT INTO products(name,price) VALUES('사과🍎 O''Brien',1200);");
        assertEquals(1,inserted.get("insertedId").longValue()); assertEquals("APPEND",inserted.get("accessPath").stringValue());
        var selected=success("SELECT * FROM products WHERE id=1;");
        assertEquals("사과🍎 O'Brien",selected.get("rows").get(0).get("name").stringValue());
        assertEquals("PRIMARY_KEY_INDEX",selected.get("accessPath").stringValue());
        assertEquals("TABLE_SCAN",success("SELECT id FROM products WHERE price=1200;").get("accessPath").stringValue());
        assertEquals(0,success("SELECT * FROM products WHERE id=999;").get("rows").size());
        assertEquals("application/json; charset=utf-8",sql("SELECT * FROM products;").headers().firstValue("Content-Type").orElseThrow());
    }
    @Test void rangesCrossLeafBoundariesAndRespectSqlAndHttpLimits() throws Exception {
        for (int i = 1; i <= 1100; i++) database.execute(new SqlStatement.Insert("product-" + i, i));
        var result = success("SELECT id FROM products WHERE id BETWEEN 27 AND 101 LIMIT 70;");
        assertEquals("PRIMARY_KEY_RANGE", result.get("accessPath").stringValue());
        var rows = result.get("rows");
        assertEquals(70, rows.size());
        for (int i = 0; i < rows.size(); i++) assertEquals(27 + i, rows.get(i).get("id").longValue());
        assertEquals(0, success("SELECT id FROM products WHERE id BETWEEN 10 AND -1;").get("rows").size());
        assertEquals(413, sql("SELECT id FROM products WHERE id BETWEEN 1 AND 1100;").statusCode());
        assertEquals(400, sql("SELECT id FROM products WHERE price BETWEEN 1 AND 7;").statusCode());
    }

    @ParameterizedTest @ValueSource(strings={"", "{", "null", "[]", "{}", "{\"sql\":null}", "{\"sql\":1}",
            "{\"sql\":\"SELECT * FROM products;\",\"other\":1}", "{\"sql\":\"a\",\"sql\":\"b\"}", "{\"sql\":\"SELECT * FROM products;\"} {}"})
    void invalidJsonDoesNotChangeTheStore(String body) throws Exception {
        var response=send("/query","POST",body,"application/json");
        assertEquals(400,response.statusCode()); assertEquals("INVALID_REQUEST",json.readTree(response.body()).get("error").stringValue());
        assertEquals(0,database.validateIndex().entries());
    }
    @ParameterizedTest @ValueSource(strings={"", "CREATE TABLE x(id INT);", "SELECT * FROM missing;", "SELECT * FROM products WHERE id='x';",
            "INSERT INTO products(name,price) VALUES('a',-1);", "INSERT INTO products(name,price) VALUES('a',1); SELECT * FROM products;"})
    void invalidOrMultipleSqlStatementsNeverInsert(String text) throws Exception {
        var response=sql(text); assertEquals(400,response.statusCode());
        assertEquals("INVALID_SQL",json.readTree(response.body()).get("error").stringValue()); assertEquals(0,database.validateIndex().entries());
    }
    @Test void validatesRoutesMethodsContentTypeBodySizeAndUtf8BeforeExecution() throws Exception {
        assertEquals(404,send("/query-extra","POST","{}","application/json").statusCode());
        assertEquals(404,send("/q%75ery","POST","{}","application/json").statusCode());
        assertEquals(400,send("/query?x=1","POST","{}","application/json").statusCode());
        var method=send("/query","GET",null,null); assertEquals(405,method.statusCode());
        assertEquals("POST",method.headers().firstValue("Allow").orElseThrow());
        assertEquals(415,send("/query","POST","{}","text/plain").statusCode());
        assertEquals(413,send("/query","POST","x".repeat(1_048_577),"application/json").statusCode());
        byte[] utf16="{\"sql\":\"SELECT * FROM products;\"}".getBytes(java.nio.charset.StandardCharsets.UTF_16BE);
        var request=HttpRequest.newBuilder(base.resolve("/query")).timeout(Duration.ofSeconds(3)).header("Content-Type","application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(utf16)).build();
        assertEquals(400,client.send(request,HttpResponse.BodyHandlers.ofString()).statusCode());
        assertEquals(0,database.validateIndex().entries());
    }
    @Test void truncatedRequestTransportDoesNotPoisonTheDatabase() throws Exception {
        try(var socket=new java.net.Socket("127.0.0.1",server.port())) {
            socket.setSoTimeout(1000);
            socket.getOutputStream().write(("POST /query HTTP/1.1\r\nHost: 127.0.0.1\r\nContent-Type: application/json\r\nContent-Length: 100\r\n\r\n{}")
                    .getBytes(java.nio.charset.StandardCharsets.US_ASCII));
            socket.getOutputStream().flush(); socket.shutdownOutput();
            try { socket.getInputStream().readAllBytes(); }
            catch(java.io.IOException disconnected) { /* peer may close on a truncated HTTP body */ }
        }
        assertEquals(0,success("SELECT * FROM products;").get("rows").size());
        assertEquals(1,success("INSERT INTO products(name,price) VALUES('healthy',1);").get("insertedId").longValue());
    }

    @Test void resultCountAndByteBudgetsRejectRatherThanSilentlyTruncateOrMutate() throws Exception {
        for(int i=0;i<1001;i++) database.execute(new SqlStatement.Insert("small",i));
        var oversized=sql("SELECT * FROM products;"); assertEquals(413,oversized.statusCode());
        assertEquals("RESULT_TOO_LARGE",json.readTree(oversized.body()).get("error").stringValue());
        assertEquals(1000,success("SELECT * FROM products LIMIT 1000;").get("rows").size());
        assertEquals(1,success("SELECT id FROM products WHERE id=1001;").get("rows").size());
        for(int i=0;i<6;i++) database.execute(new SqlStatement.Insert("x".repeat(800_000),9999));
        assertEquals(413,sql("SELECT * FROM products WHERE price=9999;").statusCode());
        assertEquals(1,success("SELECT * FROM products WHERE price=9999 LIMIT 1;").get("rows").size());
        assertEquals(1007,database.validateIndex().entries());
    }
    @Test void parallelInsertsHaveUniqueIdsAndFileIndexAgreementAfterReopen() throws Exception {
        var expected=new HashMap<Long,String>();
        try(var callers=Executors.newFixedThreadPool(16)) {
            var futures=new ArrayList<Future<Map<Long,String>>>();
            for(int w=0;w<16;w++) {
                int worker=w;
                futures.add(callers.submit(()->{
                    var own=new HashMap<Long,String>();
                    for(int i=0;i<32;i++) {
                        String name="parallel-"+worker+"-"+i;
                        long id=success("INSERT INTO products(name,price) VALUES('"+name+"',"+i+");").get("insertedId").longValue();
                        assertNull(own.put(id,name));
                        assertEquals(name,success("SELECT name FROM products WHERE id="+id+";").get("rows").get(0).get("name").stringValue());
                    }
                    return own;
                }));
            }
            for(var future:futures) future.get(30,TimeUnit.SECONDS).forEach((id,name)->assertNull(expected.put(id,name)));
        }
        assertEquals(512,expected.size()); for(long id=1;id<=512;id++) assertTrue(expected.containsKey(id));
        assertEquals(512,database.validateIndex().entries());
        var scan=success("SELECT * FROM products;").get("rows"); assertEquals(512,scan.size());
        for(var row:scan) assertEquals(expected.get(row.get("id").longValue()),row.get("name").stringValue());
        server.close(); database.close();
        assertEquals(512,Files.readAllLines(directory.resolve("products.jsonl")).size());
        database=new Database(directory);
        assertEquals(512,database.validateIndex().entries());
        var engine=new SqlEngine(database);
        for(var entry:expected.entrySet()) assertEquals(entry.getValue(),engine.execute("SELECT name FROM products WHERE id="+entry.getKey()+";").getFirst().rows().getFirst().get("name"));
        assertEquals(513,engine.execute("INSERT INTO products(name,price) VALUES('after restart',1);").getFirst().insertedId());
    }
    @Test void closingBeforeStartIsIdempotentAndDoesNotBindThePortOrOwnTheDatabase() throws Exception {
        var unstarted=new QueryHttpServer(new InetSocketAddress("127.0.0.1",server.port()),database);
        unstarted.close(); unstarted.close(); assertThrows(IllegalStateException.class,unstarted::start);
        assertEquals(0,success("SELECT * FROM products;").get("rows").size());
        assertEquals(1,database.execute(new SqlStatement.Insert("still open",1)).insertedId());
    }
}
