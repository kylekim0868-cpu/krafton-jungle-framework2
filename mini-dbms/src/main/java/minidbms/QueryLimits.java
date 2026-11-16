package minidbms;

/** HTTP만 유한한 행수/행바이트 예산을 적용한다. CLI는 호출자가 LIMIT를 선택한다. */
public record QueryLimits(int maxRows, long maxRowBytes) {
    public static final QueryLimits UNLIMITED = new QueryLimits(Integer.MAX_VALUE, Long.MAX_VALUE);
    public QueryLimits {
        if (maxRows < 1 || maxRowBytes < 1) throw new IllegalArgumentException("query limits must be positive");
    }
}
