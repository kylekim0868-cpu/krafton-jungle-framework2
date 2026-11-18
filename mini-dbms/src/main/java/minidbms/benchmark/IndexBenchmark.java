package minidbms.benchmark;

import minidbms.*;
import minidbms.index.BPlusTree;
import tools.jackson.databind.json.JsonMapper;
import java.nio.file.*;
import java.util.*;

/** 동일한 ID SQL을 인덱스 사용과 선형 탐색으로 교대 실행한다. 운영 최대 성능 측정이 아니다. */
public final class IndexBenchmark {
    public record Summary(int samples,double p50Ms,double p95Ms,double operationsPerSecond,long activeNanos) { }
    public record Result(String java,String os,String architecture,int rows,int warmup,int samples,long seed,
                         long insertAndCloseNanos,long reopenAndRebuildNanos,long fileBytes,BPlusTree.Structure structure,
                         long[] keys,long[] idNanos,long[] scanNanos,Summary idIndex,Summary idScan) { }
    private IndexBenchmark() { }

    public static Result run(Path directory,int rows,int samples,int warmup) throws Exception {
        if(rows<1 || rows>5_000_000 || samples<1 || samples>1000 || warmup<1 || warmup>1000) throw new IllegalArgumentException("invalid experiment size");
        Path table=directory.resolve("products.jsonl");
        if(Files.exists(table) && Files.size(table)!=0) throw new IllegalArgumentException("benchmark requires a fresh data directory");
        long started=System.nanoTime();
        try(var db=new Database(directory)) {
            if(db.validateIndex().entries()!=0) throw new IllegalArgumentException("benchmark requires an empty owned database");
            for(int i=1;i<=rows;i++) {
                var result=db.execute(new SqlStatement.Insert("product-"+i,i%10_000));
                if(result.insertedId()!=i) throw new IllegalStateException("wrong inserted ID");
            }
            if(db.validateIndex().entries()!=rows) throw new IllegalStateException("missing inserted entries");
        }
        long inserted=System.nanoTime()-started;
        started=System.nanoTime();
        try(var db=new Database(directory)) {
            long rebuilt=System.nanoTime()-started;
            var structure=db.validateIndex();
            if(structure.entries()!=rows) throw new IllegalStateException("wrong rebuilt entry count");
            long[] keys=new long[samples]; var random=new Random(621_017);
            for(int i=0;i<samples;i++) keys[i]=random.nextLong(rows)+1;
            keys[0]=1; if(samples>1) keys[samples-1]=rows;
            var engine=new SqlEngine(db);
            var scanEngine=new SqlEngine(db,false);
            for(int i=0;i<warmup;i++) { query(engine,keys[i%samples],true); query(scanEngine,keys[i%samples],false); }
            long[] id=new long[samples], scan=new long[samples];
            for(int i=0;i<samples;i++) {
                if(i%2==0) { id[i]=query(engine,keys[i],true); scan[i]=query(scanEngine,keys[i],false); }
                else { scan[i]=query(scanEngine,keys[i],false); id[i]=query(engine,keys[i],true); }
            }
            return new Result(System.getProperty("java.runtime.version"),System.getProperty("os.name")+" "+System.getProperty("os.version"),
                    System.getProperty("os.arch"),rows,warmup,samples,621_017,inserted,rebuilt,Files.size(table),structure,keys,id,scan,summary(id),summary(scan));
        }
    }

    private static long query(SqlEngine engine,long key,boolean indexed) throws Exception {
        String sql="SELECT * FROM products WHERE id="+key+" LIMIT 1;";
        long start=System.nanoTime();
        var result=engine.execute(sql).getFirst();
        long elapsed=System.nanoTime()-start;
        String path=indexed?"PRIMARY_KEY_INDEX":"TABLE_SCAN";
        if(!result.accessPath().equals(path) || result.rows().size()!=1
                || !result.rows().getFirst().equals(Map.of("id",key,"name","product-"+key,"price",key%10_000))) {
            throw new IllegalStateException("benchmark access path or result differs");
        }
        return elapsed;
    }

    public static Summary summary(long[] values) {
        if(values.length==0) throw new IllegalArgumentException("empty samples");
        long[] sorted=values.clone(); long total=0;
        for(long value:values) { if(value<=0) throw new IllegalArgumentException("invalid latency"); total=Math.addExact(total,value); }
        Arrays.sort(sorted);
        return new Summary(values.length,sorted[(int)Math.ceil(values.length*.5)-1]/1e6,
                sorted[(int)Math.ceil(values.length*.95)-1]/1e6,values.length*1e9/total,total);
    }

    public static void main(String[] args) throws Exception {
        if(args.length!=4) throw new IllegalArgumentException("Usage: IndexBenchmark <fresh-data-directory> <rows>=1000000 <samples> <warmup>");
        int rows=Integer.parseInt(args[1]);
        if(rows<1_000_000) throw new IllegalArgumentException("CLI benchmark requires at least one million rows");
        var result=run(Path.of(args[0]),rows,Integer.parseInt(args[2]),Integer.parseInt(args[3]));
        System.out.writeBytes((JsonMapper.builder().build().writeValueAsString(result)+"\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        if(System.out.checkError()) throw new java.io.IOException("stdout write failed");
    }
}
