package miniredis.persistence;

/** expiresAt=null이면 영구 저장. 절대 epoch ms를 보존해 재시작이 TTL을 연장하지 않는다. */
public record Mutation(String op, String key, String value, Long expiresAt) {
    public Mutation {
        if (key == null || !("PUT".equals(op) || "DELETE".equals(op))
                || (op.equals("PUT") && value == null)
                || (op.equals("DELETE") && (value != null || expiresAt != null))) {
            throw new IllegalArgumentException("invalid mutation");
        }
    }
}
