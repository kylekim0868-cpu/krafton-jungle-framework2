package minidbms;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(20)
class IndexedDatabaseTest {
    @TempDir Path directory;
    @Test void indexedIdsEqualScannedRowsBeforeAndAfterRebuildingFromFile() throws Exception {
        try(var db=new Database(directory)) {
            for(int i=0;i<4000;i++) db.execute(new SqlStatement.Insert("사과🍎-"+i,i));
            assertTrue(db.validateIndex().height()>=3);
            compare(db);
        }
        try(var db=new Database(directory)) {
            assertEquals(4000,db.validateIndex().entries()); compare(db);
            var result=db.execute(new SqlStatement.Insert("after restart",1));
            assertEquals(4001,result.insertedId());
            assertEquals("after restart",new SqlEngine(db).execute("SELECT name FROM products WHERE id=4001;").getFirst().rows().getFirst().get("name"));
        }
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans={false,true})
    void partialIoCheckedOrUncheckedFailuresBlockReadsAndWritesUntilValidatedRestart(boolean unchecked) throws Exception {
        var armed=new java.util.concurrent.atomic.AtomicBoolean();
        var calls=new java.util.concurrent.atomic.AtomicInteger();
        TableFile.Writer writer=(channel,bytes)->{
            calls.incrementAndGet();
            if(armed.get()) {
                bytes.limit(bytes.position()+3);
                channel.write(bytes);
                if(unchecked) throw new AssertionError("injected unchecked I/O failure");
                throw new java.io.IOException("injected checked I/O failure");
            }
            return channel.write(bytes);
        };
        try(var db=new Database(directory,writer)) {
            db.execute(new SqlStatement.Insert("old",1));
            armed.set(true);
            if(unchecked) assertThrows(AssertionError.class,()->db.execute(new SqlStatement.Insert("partial",2)));
            else assertThrows(java.io.IOException.class,()->db.execute(new SqlStatement.Insert("partial",2)));
            assertThrows(java.io.IOException.class,()->new SqlEngine(db).execute("SELECT * FROM products WHERE id=1;"));
            assertThrows(java.io.IOException.class,()->db.execute(new SqlStatement.Insert("blocked",3)));
            assertThrows(IllegalStateException.class,db::validateIndex);
            assertEquals(2,calls.get());
        }
        assertThrows(java.io.IOException.class,()->new Database(directory)); // no silent repair of the partial tail
    }

    private static void compare(Database db) throws Exception {
        var engine=new SqlEngine(db);
        var scanned=engine.execute("SELECT * FROM products;").getFirst();
        assertEquals("TABLE_SCAN",scanned.accessPath());
        for(var row:scanned.rows()) {
            long id=(Long)row.get("id");
            var indexed=engine.execute("SELECT * FROM products WHERE id="+id+";").getFirst();
            assertEquals("PRIMARY_KEY_INDEX",indexed.accessPath()); assertEquals(List.of(row),indexed.rows());
        }
        for(long id:new long[]{-1,0,4001,Long.MAX_VALUE}) {
            var result=engine.execute("SELECT * FROM products WHERE id="+id+";").getFirst();
            assertTrue(result.rows().isEmpty()); assertEquals("PRIMARY_KEY_INDEX",result.accessPath());
        }
        var name=engine.execute("SELECT id FROM products WHERE name='사과🍎-42' LIMIT 1;").getFirst();
        assertEquals("TABLE_SCAN",name.accessPath()); assertEquals(List.of(Map.of("id",43L)),name.rows());
    }
}
