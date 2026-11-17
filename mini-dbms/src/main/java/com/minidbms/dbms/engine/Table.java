package com.minidbms.dbms.engine;

import com.minidbms.dbms.DbException;
import com.minidbms.dbms.index.BPlusTree;
import com.minidbms.dbms.storage.RowCodec;
import com.minidbms.dbms.storage.Schema;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * 테이블 하나 = 데이터 파일 하나({@code <table>.tbl}) + 메모리의 B+ 트리 인덱스.
 *
 * <ul>
 *   <li>데이터 파일은 뒤에만 붙여 쓰는(append-only) 텍스트 파일이다. 한 줄이 행 하나.</li>
 *   <li>인덱스는 {@code id → 그 줄이 시작하는 바이트 위치} 를 기억한다.
 *       그래서 {@code WHERE id = ?} 는 파일을 처음부터 읽지 않고 해당 위치로 바로 점프한다.</li>
 *   <li>인덱스는 메모리에만 있으므로, 프로그램을 켤 때 파일을 한 번 훑어서 다시 만든다.</li>
 * </ul>
 *
 * <p><b>동시성</b>: 테이블마다 읽기-쓰기 락을 하나 둔다.
 * SELECT 는 읽기 락(여러 스레드 동시 가능), INSERT 는 쓰기 락(혼자만 가능)을 잡는다.
 * 읽는 쪽은 파일 위치를 지정해서 읽으므로(positional read) 서로 방해하지 않는다.</p>
 */
public final class Table implements Closeable {

    /** 행 하나를 받아 처리한다. false 를 돌려주면 순회를 멈춘다. */
    @FunctionalInterface
    public interface RowVisitor {
        boolean visit(Object[] row);
    }

    @FunctionalInterface
    private interface LineVisitor {
        boolean visit(long offset, String line);
    }

    private static final int SCAN_BUFFER_SIZE = 1 << 16;

    private final Schema schema;
    private final Path dataFile;
    private final FileChannel channel;
    private final BPlusTree index = new BPlusTree();
    /** fair=true: 읽기 요청이 계속 들어와도 쓰기 요청이 굶지 않게 한다 */
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock(true);

    /** 유효한 데이터의 끝 위치. 다음 INSERT 는 여기에 기록된다. */
    private long fileLength;
    private long nextId = 1;

    public Table(Path dataFile, Schema schema) throws IOException {
        this.schema = schema;
        this.dataFile = dataFile;
        this.channel = FileChannel.open(dataFile,
                StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE);
        try {
            recover();
        } catch (IOException | RuntimeException e) {
            channel.close();
            throw e;
        }
    }

    /**
     * 시작 시 복구: 파일 전체를 훑어 인덱스를 다시 만들고 다음 id 를 정한다.
     * 마지막 줄이 줄바꿈 없이 끝났다면 쓰는 도중 프로그램이 죽은 것이므로 그 조각은 잘라 낸다.
     */
    private void recover() throws IOException {
        long physicalSize = channel.size();
        long[] maxId = {0};
        long validEnd = scanLines(physicalSize, (offset, line) -> {
            long id = RowCodec.decodeId(line);
            index.insert(id, offset);
            maxId[0] = Math.max(maxId[0], id);
            return true;
        });
        if (validEnd < physicalSize) {
            channel.truncate(validEnd);
        }
        fileLength = validEnd;
        nextId = maxId[0] + 1;
    }

    public Schema schema() {
        return schema;
    }

    // ------------------------------------------------------------------
    // 쓰기
    // ------------------------------------------------------------------

    /**
     * 여러 행을 한 번에 추가한다. id 는 자동으로 부여되어 각 행의 0번 칸에 채워진다.
     *
     * @param rows 스키마 크기와 같은 길이의 배열들 (0번 칸은 비워 둔다)
     * @return 첫 번째 행에 부여된 id (이후 행은 +1 씩 증가)
     */
    public long insert(List<Object[]> rows) {
        lock.writeLock().lock();
        try {
            long firstId = nextId;
            long[] offsets = new long[rows.size()];
            ByteArrayOutputStream batch = new ByteArrayOutputStream(rows.size() * 48);

            for (int i = 0; i < rows.size(); i++) {
                Object[] row = rows.get(i);
                row[0] = firstId + i;
                offsets[i] = fileLength + batch.size();
                byte[] line = RowCodec.encode(row).getBytes(StandardCharsets.UTF_8);
                batch.write(line, 0, line.length);
                batch.write('\n');
            }

            // 1) 파일에 먼저 쓴다. 여기서 실패하면 fileLength 가 그대로라서
            //    반쯤 쓰인 내용은 다음 INSERT 가 덮어쓴다 (메모리 상태는 변하지 않음).
            ByteBuffer buffer = ByteBuffer.wrap(batch.toByteArray());
            long position = fileLength;
            while (buffer.hasRemaining()) {
                position += channel.write(buffer, position);
            }

            // 2) 쓰기에 성공한 뒤에만 인덱스와 카운터를 갱신한다.
            for (int i = 0; i < offsets.length; i++) {
                index.insert(firstId + i, offsets[i]);
            }
            fileLength = position;
            nextId = firstId + rows.size();
            return firstId;
        } catch (IOException e) {
            throw new DbException("Failed to write to table '" + schema.tableName() + "': " + e.getMessage(), e);
        } finally {
            lock.writeLock().unlock();
        }
    }

    // ------------------------------------------------------------------
    // 읽기
    // ------------------------------------------------------------------

    /** 인덱스로 행 하나를 찾는다: B+ 트리에서 파일 위치를 얻고 → 그 위치의 한 줄만 읽는다. */
    public Object[] findById(long id) {
        lock.readLock().lock();
        try {
            long offset = index.search(id);
            if (offset == BPlusTree.NOT_FOUND) {
                return null;
            }
            return RowCodec.decode(readLineAt(offset), schema);
        } catch (IOException e) {
            throw readError(e);
        } finally {
            lock.readLock().unlock();
        }
    }

    /** 인덱스 범위 검색: low <= id <= high 인 행을 id 오름차순으로 방문한다. */
    public void scanIdRange(long low, long high, RowVisitor visitor) {
        lock.readLock().lock();
        try {
            IOException[] failure = {null};
            index.rangeScan(low, high, (id, offset) -> {
                try {
                    return visitor.visit(RowCodec.decode(readLineAt(offset), schema));
                } catch (IOException e) {
                    failure[0] = e;
                    return false;
                }
            });
            if (failure[0] != null) {
                throw readError(failure[0]);
            }
        } finally {
            lock.readLock().unlock();
        }
    }

    /** 선형 탐색(Full Scan): 파일을 처음부터 끝까지 읽으며 모든 행을 방문한다. */
    public void scan(RowVisitor visitor) {
        lock.readLock().lock();
        try {
            scanLines(fileLength, (offset, line) -> visitor.visit(RowCodec.decode(line, schema)));
        } catch (IOException e) {
            throw readError(e);
        } finally {
            lock.readLock().unlock();
        }
    }

    public long rowCount() {
        lock.readLock().lock();
        try {
            return index.size();
        } finally {
            lock.readLock().unlock();
        }
    }

    public int indexHeight() {
        lock.readLock().lock();
        try {
            return index.height();
        } finally {
            lock.readLock().unlock();
        }
    }

    // ------------------------------------------------------------------
    // 파일 읽기 도우미
    // ------------------------------------------------------------------

    /** offset 에서 시작하는 한 줄을 읽는다. 줄 길이를 모르므로 작게 읽어 보고 모자라면 더 크게 읽는다. */
    private String readLineAt(long offset) throws IOException {
        int capacity = 256;
        while (true) {
            int length = (int) Math.min(capacity, fileLength - offset);
            byte[] bytes = new byte[length];
            readFully(ByteBuffer.wrap(bytes), offset);
            for (int i = 0; i < length; i++) {
                if (bytes[i] == '\n') {
                    return new String(bytes, 0, i, StandardCharsets.UTF_8);
                }
            }
            if (offset + length >= fileLength) {
                throw new DbException("Corrupt data file " + dataFile.getFileName() + " at offset " + offset);
            }
            capacity *= 4;
        }
    }

    private void readFully(ByteBuffer buffer, long position) throws IOException {
        while (buffer.hasRemaining()) {
            int read = channel.read(buffer, position);
            if (read < 0) {
                throw new IOException("Unexpected end of file " + dataFile.getFileName());
            }
            position += read;
        }
    }

    /**
     * 파일의 [0, endOffset) 구간을 줄 단위로 읽는다.
     * 줄바꿈 바이트(0x0A)는 UTF-8 여러 바이트 문자의 일부로 나타나지 않으므로 바이트 단위로 잘라도 안전하다.
     *
     * @return 마지막으로 완성된 줄의 끝 위치 (줄바꿈 없이 끝난 꼬리 조각은 포함하지 않는다)
     */
    private long scanLines(long endOffset, LineVisitor visitor) throws IOException {
        byte[] chunk = new byte[SCAN_BUFFER_SIZE];
        byte[] carry = new byte[256]; // 청크 경계에 걸친 줄의 앞부분
        int carryLength = 0;
        long position = 0;
        long lineStart = 0;

        while (position < endOffset) {
            int toRead = (int) Math.min(chunk.length, endOffset - position);
            readFully(ByteBuffer.wrap(chunk, 0, toRead), position);

            int segmentStart = 0;
            for (int i = 0; i < toRead; i++) {
                if (chunk[i] != '\n') {
                    continue;
                }
                String line;
                if (carryLength == 0) {
                    line = new String(chunk, segmentStart, i - segmentStart, StandardCharsets.UTF_8);
                } else {
                    carry = append(carry, carryLength, chunk, segmentStart, i - segmentStart);
                    line = new String(carry, 0, carryLength + i - segmentStart, StandardCharsets.UTF_8);
                    carryLength = 0;
                }
                long offset = lineStart;
                lineStart = position + i + 1;
                segmentStart = i + 1;
                if (!visitor.visit(offset, line)) {
                    return lineStart;
                }
            }

            int rest = toRead - segmentStart;
            carry = append(carry, carryLength, chunk, segmentStart, rest);
            carryLength += rest;
            position += toRead;
        }
        return lineStart;
    }

    /** target[0..targetLength) 뒤에 source 조각을 붙인다. 공간이 모자라면 더 큰 배열을 만들어 돌려준다. */
    private static byte[] append(byte[] target, int targetLength, byte[] source, int from, int length) {
        if (targetLength + length > target.length) {
            byte[] bigger = new byte[Math.max(target.length * 2, targetLength + length)];
            System.arraycopy(target, 0, bigger, 0, targetLength);
            target = bigger;
        }
        System.arraycopy(source, from, target, targetLength, length);
        return target;
    }

    private DbException readError(IOException e) {
        return new DbException("Failed to read table '" + schema.tableName() + "': " + e.getMessage(), e);
    }

    @Override
    public void close() throws IOException {
        lock.writeLock().lock();
        try {
            channel.force(true); // OS 버퍼에 남은 내용을 디스크로 내려 보낸다
            channel.close();
        } finally {
            lock.writeLock().unlock();
        }
    }
}
