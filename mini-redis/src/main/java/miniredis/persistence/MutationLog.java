package miniredis.persistence;

import java.io.IOException;

/** append는 전체 기록과 동기화 성공 후에만 반환한다. 실패 후 쓰기 재개는 재시작 검증이 필요하다. */
public interface MutationLog extends AutoCloseable {
    void append(Mutation mutation) throws IOException;
    @Override void close() throws IOException;
}
