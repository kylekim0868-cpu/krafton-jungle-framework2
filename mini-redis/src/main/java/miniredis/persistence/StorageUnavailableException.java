package miniredis.persistence;

/** HTTP 경계에서503으로 변환한다. 실패한 기록이 재시작 후 나타날 가능성은 배제하지 않는다. */
public final class StorageUnavailableException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public StorageUnavailableException(String message, Throwable cause) { super(message, cause); }
}
