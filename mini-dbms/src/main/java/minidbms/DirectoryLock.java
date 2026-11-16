package minidbms;

import java.io.IOException;
import java.nio.channels.*;
import java.nio.file.*;
import java.util.HashSet;
import java.util.Set;
import static java.nio.file.StandardOpenOption.*;

/** 동일 데이터 디렉터리의 프로세스 중복 소유를 막는다. 활성 파일의 외부 교체는 금지한다. */
final class DirectoryLock implements AutoCloseable {
    private static final Set<DirectoryLock> OWNERS = new HashSet<>();
    private final Path path;
    private final FileChannel channel;
    private final FileLock lock;
    private boolean closed;
    private DirectoryLock(Path path, FileChannel channel, FileLock lock) { this.path = path; this.channel = channel; this.lock = lock; }

    static synchronized DirectoryLock acquire(Path directory) throws IOException {
        Files.createDirectories(directory);
        Path file = directory.toRealPath().resolve(".minidbms.lock");
        for (var owner : OWNERS) {
            if (file.equals(owner.path) || (Files.exists(file) && Files.isSameFile(file, owner.path))) {
                throw new IOException("database already open in this JVM");
            }
        }
        var channel = FileChannel.open(file, CREATE, WRITE);
        try {
            var lock = channel.tryLock();
            if (lock == null) throw new IOException("database already owned by another process");
            var owner = new DirectoryLock(file, channel, lock); OWNERS.add(owner); return owner;
        } catch (IOException | RuntimeException | Error failure) {
            try { channel.close(); } catch (IOException closeFailure) { failure.addSuppressed(closeFailure); }
            throw failure;
        }
    }

    @Override public void close() throws IOException {
        synchronized (DirectoryLock.class) {
            if (closed) return;
            closed = true;
            try { lock.release(); } finally {
                try { channel.close(); } finally { if (!channel.isOpen()) OWNERS.remove(this); }
            }
        }
    }
}
