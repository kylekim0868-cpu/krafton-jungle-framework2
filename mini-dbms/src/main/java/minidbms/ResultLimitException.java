package minidbms;

public final class ResultLimitException extends RuntimeException {
    private static final long serialVersionUID = 1L;
    public ResultLimitException() { super("query exceeds result budget; use a smaller LIMIT or narrower WHERE"); }
}
