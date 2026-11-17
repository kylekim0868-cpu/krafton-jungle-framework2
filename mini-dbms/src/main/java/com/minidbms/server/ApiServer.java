package com.minidbms.server;

import com.minidbms.dbms.DbException;
import com.minidbms.dbms.engine.QueryResult;
import com.minidbms.dbms.engine.SqlEngine;
import com.minidbms.dbms.engine.Table;
import com.minidbms.dbms.sql.ColumnDef;
import com.minidbms.dbms.sql.Parser;
import com.minidbms.dbms.sql.Statement;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 미니 DBMS 의 HTTP API 서버. 프레임워크 없이 {@link ServerSocket} 으로 직접 구현했다.
 *
 * <pre>
 *   클라이언트 ──TCP 연결──▶ [accept 스레드] ──작업 제출──▶ [ThreadPool 대기 큐] ──▶ [워커 스레드]
 *                              (연결만 받는다)                                      HTTP 요청 읽기
 *                                                                                  → SqlEngine 실행
 *                                                                                  → JSON 응답 쓰기
 * </pre>
 *
 * <ul>
 *   <li>accept 스레드는 연결을 받아 스레드 풀에 넘기기만 한다. 느린 요청 하나가 다른 연결의 수락을 막지 않는다.</li>
 *   <li>워커 여러 개가 같은 {@link SqlEngine} 을 동시에 호출한다.
 *       데이터 보호는 테이블의 읽기-쓰기 락이 담당한다 (SELECT 끼리는 동시에, INSERT 는 혼자).</li>
 *   <li>큐가 가득 차면 accept 스레드가 즉시 503 을 돌려준다 (과부하 시 빠른 실패).</li>
 * </ul>
 *
 * <p><b>엔드포인트</b></p>
 * <pre>
 *   POST /query    본문: SQL 텍스트 또는 {"sql": "..."}   → 실행 결과
 *   GET  /tables   테이블 목록과 스키마
 *   GET  /stats    스레드 풀 상태
 *   GET  /health   서버 상태 확인
 * </pre>
 */
public final class ApiServer implements AutoCloseable {

    private static final int MAX_HEADER_BYTES = 16 * 1024;
    private static final int MAX_BODY_BYTES = 1024 * 1024;
    private static final int SOCKET_TIMEOUT_MS = 10_000;
    /** SELECT 한 번이 응답에 담을 수 있는 최대 행 수 (거대한 JSON 으로 메모리가 터지는 것을 막는다) */
    public static final long MAX_RESULT_ROWS = 10_000;

    private final SqlEngine engine;
    private final ThreadPool pool;
    private final ServerSocket serverSocket;
    private final Thread acceptThread;
    private volatile boolean running;

    public ApiServer(SqlEngine engine, int port, int threadCount, int queueCapacity) throws IOException {
        this.engine = engine;
        this.engine.setMaxResultRows(MAX_RESULT_ROWS);
        this.serverSocket = new ServerSocket(port); // 포트를 못 열면 여기서 실패하고, 아래 스레드들은 만들어지지 않는다
        this.pool = new ThreadPool(threadCount, queueCapacity);
        this.acceptThread = new Thread(this::acceptLoop, "http-accept");
    }

    public void start() {
        running = true;
        acceptThread.start();
    }

    /** 실제로 열린 포트 (생성자에 0 을 주면 OS 가 빈 포트를 골라 준다) */
    public int port() {
        return serverSocket.getLocalPort();
    }

    public ThreadPool pool() {
        return pool;
    }

    private void acceptLoop() {
        while (running) {
            Socket socket;
            try {
                socket = serverSocket.accept();
            } catch (IOException e) {
                if (running) {
                    System.err.println("[http-accept] accept failed: " + e.getMessage());
                }
                continue;
            }
            // 요청이 들어올 때마다 스레드 풀의 워커에게 처리를 맡긴다
            if (!pool.trySubmit(() -> handleConnection(socket))) {
                rejectBusy(socket);
            }
        }
    }

    /** 거절할 때 클라이언트의 요청을 기다려 주는 최대 시간. accept 스레드를 오래 붙잡지 않도록 짧게 둔다. */
    private static final int REJECT_LINGER_MS = 200;

    /**
     * 큐가 가득 찼을 때: 요청을 처리하지 않고 바로 503 을 보낸다.
     *
     * <p>응답만 쓰고 곧바로 닫으면, 뒤늦게 도착한 요청 데이터 때문에 OS 가 연결 리셋(RST)을 보내고
     * 클라이언트는 503 대신 "connection reset" 오류를 보게 된다. 그래서 응답을 쓴 뒤
     * 요청 헤더가 다 도착할 때까지(최대 {@value #REJECT_LINGER_MS}ms) 읽어서 버린 다음 닫는다.</p>
     */
    private void rejectBusy(Socket socket) {
        try (socket) {
            writeResponse(socket.getOutputStream(), 503,
                    Json.write(errorBody("Server is busy, try again later")));
            socket.shutdownOutput();

            InputStream in = socket.getInputStream();
            long deadline = System.currentTimeMillis() + REJECT_LINGER_MS;
            int matched = 0;   // "\r\n\r\n" 중 지금까지 맞은 글자 수
            int total = 0;
            while (matched < 4 && total < MAX_HEADER_BYTES) {
                long remaining = deadline - System.currentTimeMillis();
                if (remaining <= 0) {
                    break;
                }
                socket.setSoTimeout((int) remaining);
                int b = in.read();
                if (b < 0) {
                    break;
                }
                total++;
                if (b == (matched % 2 == 0 ? '\r' : '\n')) {
                    matched++;
                } else {
                    matched = b == '\r' ? 1 : 0;
                }
            }
        } catch (IOException ignored) {
            // 시간 초과이거나 클라이언트가 먼저 끊은 경우
        }
    }

    // ------------------------------------------------------------------
    // 연결 하나 처리 (워커 스레드에서 실행)
    // ------------------------------------------------------------------

    private void handleConnection(Socket socket) {
        try (socket) {
            socket.setSoTimeout(SOCKET_TIMEOUT_MS);
            InputStream in = new BufferedInputStream(socket.getInputStream());
            OutputStream out = socket.getOutputStream();

            int status;
            String body;
            try {
                HttpRequest request = readRequest(in, out);
                if (request == null) {
                    return; // 아무것도 보내지 않고 끊은 연결
                }
                Object[] response = route(request);
                status = (int) response[0];
                body = Json.write(response[1]);
            } catch (HttpError e) {
                status = e.status;
                body = Json.write(errorBody(e.getMessage()));
            } catch (SocketTimeoutException e) {
                status = 408;
                body = Json.write(errorBody("Request timed out"));
            }
            writeResponse(out, status, body);
        } catch (SocketException ignored) {
            // 클라이언트가 응답을 받기 전에 끊은 경우
        } catch (IOException e) {
            System.err.println("[" + Thread.currentThread().getName() + "] I/O error: " + e.getMessage());
        }
    }

    /** @return {HTTP 상태 코드, JSON 으로 바꿀 응답 객체} */
    private Object[] route(HttpRequest request) {
        try {
            switch (request.path) {
                case "/query":
                    requireMethod(request, "POST");
                    return query(request);
                case "/tables":
                    requireMethod(request, "GET");
                    return new Object[]{200, tables()};
                case "/stats":
                    requireMethod(request, "GET");
                    return new Object[]{200, stats()};
                case "/health":
                    requireMethod(request, "GET");
                    return new Object[]{200, Map.of("status", "ok")};
                default:
                    return new Object[]{404, errorBody("No such endpoint: " + request.path)};
            }
        } catch (HttpError e) {
            return new Object[]{e.status, errorBody(e.getMessage())};
        } catch (RuntimeException e) {
            // 예상하지 못한 버그. 서버는 죽지 않고 500 으로 응답한다.
            System.err.println("[" + Thread.currentThread().getName() + "] internal error: " + e);
            return new Object[]{500, errorBody("Internal server error")};
        }
    }

    private Object[] query(HttpRequest request) {
        String sql = extractSql(request);
        if (sql.isBlank()) {
            throw new HttpError(400, "Request body must contain SQL");
        }

        List<Object> results = new ArrayList<>();
        try {
            // 문법 오류는 아무것도 실행하기 전에 걸러진다
            List<Statement> statements = Parser.parseScript(sql);
            if (statements.isEmpty()) {
                throw new HttpError(400, "Request body must contain SQL");
            }
            for (Statement statement : statements) {
                results.add(toJson(engine.execute(statement)));
            }
        } catch (DbException e) {
            Map<String, Object> body = errorBody(e.getMessage());
            // 여러 문장 중 앞부분이 이미 실행됐다면 몇 개가 반영됐는지 알려 준다
            body.put("completedStatements", results.size());
            return new Object[]{400, body};
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", true);
        body.put("results", results);
        return new Object[]{200, body};
    }

    /** 본문이 JSON 이면 "sql" 필드를, 아니면 본문 전체를 SQL 로 본다. */
    private static String extractSql(HttpRequest request) {
        String body = new String(request.body, StandardCharsets.UTF_8);
        String contentType = request.headers.getOrDefault("content-type", "");
        if (!contentType.toLowerCase(Locale.ROOT).contains("json")) {
            return body;
        }
        Object parsed;
        try {
            parsed = Json.parse(body);
        } catch (IllegalArgumentException e) {
            throw new HttpError(400, e.getMessage());
        }
        if (parsed instanceof Map<?, ?> map && map.get("sql") instanceof String sql) {
            return sql;
        }
        throw new HttpError(400, "JSON body must be an object with a string field \"sql\"");
    }

    private static Map<String, Object> toJson(QueryResult result) {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("type", result.type());
        if (result.type().equals("SELECT")) {
            json.put("columns", result.columns());
            json.put("rows", result.rows());
            json.put("rowCount", result.rows().size());
            json.put("accessPath", result.accessPath());
            json.put("truncated", result.truncated());
        } else if (result.type().equals("INSERT")) {
            json.put("affected", result.affected());
            json.put("firstInsertId", result.firstInsertId());
            json.put("lastInsertId", result.firstInsertId() + result.affected() - 1);
        }
        json.put("message", result.message());
        json.put("elapsedMs", Math.round(result.elapsedMs() * 1000) / 1000.0);
        return json;
    }

    // ------------------------------------------------------------------
    // 서버 정보
    // ------------------------------------------------------------------

    private Map<String, Object> tables() {
        List<Object> list = new ArrayList<>();
        for (Map.Entry<String, Table> entry : engine.database().tables().entrySet()) {
            Table table = entry.getValue();
            List<Object> columns = new ArrayList<>();
            for (ColumnDef column : table.schema().columns()) {
                Map<String, Object> c = new LinkedHashMap<>();
                c.put("name", column.name());
                c.put("type", column.type().name());
                columns.add(c);
            }
            Map<String, Object> t = new LinkedHashMap<>();
            t.put("name", entry.getKey());
            t.put("columns", columns);
            t.put("rowCount", table.rowCount());
            t.put("indexHeight", table.indexHeight());
            list.add(t);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("tables", list);
        return body;
    }

    private Map<String, Object> stats() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("threads", pool.threadCount());
        body.put("active", pool.activeCount());
        body.put("queued", pool.queuedCount());
        body.put("queueCapacity", pool.queueCapacity());
        body.put("completed", pool.completedCount());
        body.put("rejected", pool.rejectedCount());
        return body;
    }

    private static Map<String, Object> errorBody(String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", false);
        body.put("error", message);
        return body;
    }

    private static void requireMethod(HttpRequest request, String method) {
        if (!request.method.equals(method)) {
            throw new HttpError(405, request.path + " only supports " + method);
        }
    }

    // ------------------------------------------------------------------
    // HTTP 읽기 / 쓰기
    // ------------------------------------------------------------------

    private static final class HttpRequest {
        String method;
        String path;
        /** URL 의 ? 뒤 부분 (없으면 빈 문자열) */
        String query = "";
        final Map<String, String> headers = new LinkedHashMap<>();
        byte[] body = new byte[0];
    }

    private static final class HttpError extends RuntimeException {
        final int status;

        HttpError(int status, String message) {
            super(message);
            this.status = status;
        }
    }

    /**
     * HTTP/1.1 요청 하나를 읽는다.
     * <pre>
     *   POST /query HTTP/1.1          ← 요청 줄
     *   Content-Type: text/plain      ← 헤더들
     *   Content-Length: 21
     *                                 ← 빈 줄
     *   SELECT * FROM users;          ← 본문 (Content-Length 바이트)
     * </pre>
     */
    private static HttpRequest readRequest(InputStream in, OutputStream out) throws IOException {
        int[] budget = {MAX_HEADER_BYTES};

        String requestLine = readLine(in, budget);
        while (requestLine != null && requestLine.isEmpty()) {
            requestLine = readLine(in, budget); // 앞에 붙은 빈 줄은 무시
        }
        if (requestLine == null) {
            return null;
        }
        String[] parts = requestLine.split(" ");
        if (parts.length != 3 || !parts[2].startsWith("HTTP/")) {
            throw new HttpError(400, "Malformed request line");
        }

        HttpRequest request = new HttpRequest();
        request.method = parts[0].toUpperCase(Locale.ROOT);
        int queryStart = parts[1].indexOf('?');
        request.path = queryStart < 0 ? parts[1] : parts[1].substring(0, queryStart);
        request.query = queryStart < 0 ? "" : parts[1].substring(queryStart + 1);

        while (true) {
            String line = readLine(in, budget);
            if (line == null) {
                throw new HttpError(400, "Unexpected end of headers");
            }
            if (line.isEmpty()) {
                break;
            }
            int colon = line.indexOf(':');
            if (colon <= 0) {
                throw new HttpError(400, "Malformed header");
            }
            request.headers.put(line.substring(0, colon).trim().toLowerCase(Locale.ROOT),
                    line.substring(colon + 1).trim());
        }

        if (request.headers.containsKey("transfer-encoding")) {
            throw new HttpError(411, "Chunked bodies are not supported; send Content-Length");
        }
        String lengthHeader = request.headers.get("content-length");
        if (lengthHeader == null) {
            if (request.method.equals("POST") || request.method.equals("PUT")) {
                throw new HttpError(411, "Content-Length is required");
            }
            return request;
        }

        int length;
        try {
            length = Integer.parseInt(lengthHeader);
        } catch (NumberFormatException e) {
            throw new HttpError(400, "Invalid Content-Length");
        }
        if (length < 0) {
            throw new HttpError(400, "Invalid Content-Length");
        }
        if (length > MAX_BODY_BYTES) {
            throw new HttpError(413, "Request body is larger than " + MAX_BODY_BYTES + " bytes");
        }

        // curl 은 본문이 크면 "Expect: 100-continue" 를 보내고 서버의 허락을 기다린다
        if ("100-continue".equalsIgnoreCase(request.headers.get("expect"))) {
            out.write("HTTP/1.1 100 Continue\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
            out.flush();
        }

        byte[] body = new byte[length];
        int read = 0;
        while (read < length) {
            int n = in.read(body, read, length - read);
            if (n < 0) {
                throw new HttpError(400, "Request body is shorter than Content-Length");
            }
            read += n;
        }
        request.body = body;
        return request;
    }

    /** CRLF 로 끝나는 한 줄을 읽는다. 스트림이 바로 끝났으면 null. */
    private static String readLine(InputStream in, int[] budget) throws IOException {
        ByteArrayOutputStream line = new ByteArrayOutputStream(128);
        while (true) {
            int b = in.read();
            if (b < 0) {
                if (line.size() == 0) {
                    return null;
                }
                break;
            }
            if (b == '\n') {
                break;
            }
            if (--budget[0] < 0) {
                throw new HttpError(431, "Request headers are too large");
            }
            line.write(b);
        }
        String text = line.toString(StandardCharsets.UTF_8);
        return text.endsWith("\r") ? text.substring(0, text.length() - 1) : text;
    }

    private static void writeResponse(OutputStream out, int status, String jsonBody) throws IOException {
        byte[] body = jsonBody.getBytes(StandardCharsets.UTF_8);
        String head = "HTTP/1.1 " + status + " " + reason(status) + "\r\n"
                + "Content-Type: application/json; charset=utf-8\r\n"
                + "Content-Length: " + body.length + "\r\n"
                + "Connection: close\r\n"
                + "\r\n";
        out.write(head.getBytes(StandardCharsets.US_ASCII));
        out.write(body);
        out.flush();
    }

    private static String reason(int status) {
        switch (status) {
            case 200:
                return "OK";
            case 400:
                return "Bad Request";
            case 404:
                return "Not Found";
            case 405:
                return "Method Not Allowed";
            case 408:
                return "Request Timeout";
            case 411:
                return "Length Required";
            case 413:
                return "Payload Too Large";
            case 431:
                return "Request Header Fields Too Large";
            case 503:
                return "Service Unavailable";
            default:
                return "Internal Server Error";
        }
    }

    // ------------------------------------------------------------------
    // 종료
    // ------------------------------------------------------------------

    /** 새 연결을 받지 않고, 처리 중인 요청이 끝나기를 기다린 뒤 종료한다. */
    @Override
    public void close() {
        running = false;
        try {
            serverSocket.close(); // accept() 에서 기다리던 스레드가 예외를 받고 빠져나온다
        } catch (IOException ignored) {
            // 이미 닫힌 경우
        }
        try {
            acceptThread.join(2_000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        pool.close();
    }
}
