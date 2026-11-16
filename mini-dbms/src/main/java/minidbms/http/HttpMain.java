package minidbms.http;

import minidbms.Database;
import java.net.InetSocketAddress;
import java.nio.file.Path;

public final class HttpMain {
    private static final System.Logger LOG=System.getLogger(HttpMain.class.getName());
    private HttpMain() { }
    public static void main(String[] args) throws Exception {
        if(args.length!=2) throw new IllegalArgumentException("Usage: minidbms.http.HttpMain <port> <data-directory>");
        var address=new InetSocketAddress("127.0.0.1",Integer.parseInt(args[0]));
        var database=new Database(Path.of(args[1]));
        var server=new QueryHttpServer(address,database);
        try { server.start(); }
        catch(Exception | Error failure) {
            try { server.close(); } catch(RuntimeException closeFailure) { failure.addSuppressed(closeFailure); }
            try { database.close(); } catch(java.io.IOException closeFailure) { failure.addSuppressed(closeFailure); }
            throw failure;
        }
        Runtime.getRuntime().addShutdownHook(new Thread(()->{
            try { server.close(); } finally {
                try { database.close(); }
                catch(java.io.IOException failure) { LOG.log(System.Logger.Level.ERROR,"Failed to close database",failure); }
            }
        },"mini-dbms-shutdown"));
        System.out.println("Mini DBMS listening on http://127.0.0.1:"+server.port());
    }
}
