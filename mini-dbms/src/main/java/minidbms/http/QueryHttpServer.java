package minidbms.http;

import com.sun.net.httpserver.*;
import minidbms.*;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.util.Objects;
import java.util.concurrent.*;
import static java.nio.charset.StandardCharsets.UTF_8;

/** HTTP 변환만 담당한다. 고정8개 worker가 같은 SqlEngine/Database 잠금을 공유한다. */
public final class QueryHttpServer implements AutoCloseable {
    private static final System.Logger LOG=System.getLogger(QueryHttpServer.class.getName());
    private static final int MAX_BODY=1_048_576, MAX_RESPONSE=4_194_304;
    private static final QueryLimits LIMITS=new QueryLimits(1000,MAX_RESPONSE-8192);
    private static final class RequestReadFailure extends IOException {
        private static final long serialVersionUID=1L;
        RequestReadFailure(IOException cause) { super("HTTP request body read failed",cause); }
    }
    private static final class StorageFailure extends IOException {
        private static final long serialVersionUID=1L;
        StorageFailure(IOException cause) { super("SQL storage operation failed",cause); }
    }
    private final InetSocketAddress address;
    private final SqlEngine engine;
    private final JsonMapper json=JsonMapper.builder().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS,
            DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY).build();
    private final ExecutorService workers=Executors.newFixedThreadPool(8,Thread.ofPlatform().name("mini-dbms-http-",0).factory());
    private HttpServer server;
    private boolean closed;

    public QueryHttpServer(InetSocketAddress address,Database database) {
        this.address=Objects.requireNonNull(address); engine=new SqlEngine(database);
    }
    public synchronized void start() throws IOException {
        if(closed || server!=null) throw new IllegalStateException("server cannot start");
        server=HttpServer.create(address,0);
        server.createContext("/",this::handle); server.setExecutor(workers); server.start();
    }
    public synchronized int port() {
        if(server==null) throw new IllegalStateException("server not started");
        return server.getAddress().getPort();
    }
    private void handle(HttpExchange exchange) throws IOException {
        try(exchange) {
            try { route(exchange); }
            catch(ResultLimitException limit) { sendError(exchange,413,"RESULT_TOO_LARGE"); }
            catch(IllegalArgumentException invalid) { sendError(exchange,400,"INVALID_SQL"); }
            catch(RequestReadFailure failure) {
                LOG.log(System.Logger.Level.WARNING,"HTTP request body read failed",failure);
                sendError(exchange,400,"REQUEST_READ_FAILED");
            } catch(StorageFailure failure) {
                LOG.log(System.Logger.Level.ERROR,"Database storage operation failed",failure);
                if(exchange.getResponseCode()==-1) sendError(exchange,503,"STORAGE_UNAVAILABLE"); else throw failure;
            } catch(IOException failure) {
                LOG.log(System.Logger.Level.WARNING,"HTTP response transport failed",failure);
                throw failure;
            } catch(RuntimeException failure) {
                LOG.log(System.Logger.Level.ERROR,"Database HTTP request failed",failure);
                if(exchange.getResponseCode()==-1) sendError(exchange,500,"INTERNAL_ERROR"); else throw failure;
            }
        }
    }
    private void route(HttpExchange exchange) throws IOException {
        if(!exchange.getRequestURI().getRawPath().equals("/query")) { empty(exchange,404); return; }
        if(exchange.getRequestURI().getRawQuery()!=null) { sendError(exchange,400,"INVALID_REQUEST"); return; }
        if(!exchange.getRequestMethod().equals("POST")) {
            exchange.getResponseHeaders().set("Allow","POST"); empty(exchange,405); return;
        }
        String type=exchange.getRequestHeaders().getFirst("Content-Type");
        if(type==null || !type.split(";",2)[0].trim().equalsIgnoreCase("application/json")) {
            sendError(exchange,415,"UNSUPPORTED_MEDIA_TYPE"); return;
        }
        byte[] bytes;
        try { bytes=exchange.getRequestBody().readNBytes(MAX_BODY+1); }
        catch(IOException failure) { throw new RequestReadFailure(failure); }
        if(bytes.length>MAX_BODY) { sendError(exchange,413,"REQUEST_TOO_LARGE"); return; }
        String sql;
        try {
            String text=UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
            var root=json.readTree(text);
            if(root==null || !root.isObject() || root.size()!=1 || !root.has("sql") || !root.get("sql").isString()) {
                sendError(exchange,400,"INVALID_REQUEST"); return;
            }
            sql=root.get("sql").stringValue();
        } catch(JacksonException | CharacterCodingException invalid) { sendError(exchange,400,"INVALID_REQUEST"); return; }
        QueryResult result;
        try { result=engine.executeOne(sql,LIMITS); }
        catch(IOException failure) { throw new StorageFailure(failure); }
        byte[] response=json.writeValueAsBytes(result);
        if(response.length>MAX_RESPONSE) throw new ResultLimitException();
        sendJson(exchange,200,response);
    }
    private void sendError(HttpExchange exchange,int status,String error) throws IOException {
        exchange.getResponseHeaders().set("Connection","close");
        sendJson(exchange,status,json.writeValueAsBytes(json.createObjectNode().put("error",error)));
    }
    private static void sendJson(HttpExchange exchange,int status,byte[] bytes) throws IOException {
        exchange.getResponseHeaders().set("Content-Type","application/json; charset=utf-8");
        exchange.sendResponseHeaders(status,bytes.length); exchange.getResponseBody().write(bytes);
    }
    private static void empty(HttpExchange exchange,int status) throws IOException { exchange.sendResponseHeaders(status,-1); }

    @Override public synchronized void close() {
        if(closed) return; closed=true;
        if(server!=null) server.stop(0);
        workers.shutdown();
        try {
            if(!workers.awaitTermination(10,TimeUnit.SECONDS)) {
                workers.shutdownNow();
                if(!workers.awaitTermination(10,TimeUnit.SECONDS)) throw new IllegalStateException("HTTP workers did not terminate");
            }
        } catch(InterruptedException failure) { workers.shutdownNow(); Thread.currentThread().interrupt(); }
    }
}
