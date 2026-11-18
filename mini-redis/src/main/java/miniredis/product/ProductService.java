package miniredis.product;

import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

/** Cache-aside: 캐시 실패는 원본 조회를 막지 않고 성공한 원본 변경 뒤 무효화한다. */
public final class ProductService {
    private final ProductOrigin origin;
    private final HttpKeyCache cache; // null은 비교 실험의 캐시 미사용 모드다.
    private final long ttlMs;
    private final AtomicLong cacheFailures = new AtomicLong();
    private final JsonMapper json = JsonMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS,
                    DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY).build();

    public ProductService(ProductOrigin origin, HttpKeyCache cache, long ttlMs) {
        this.origin = Objects.requireNonNull(origin, "origin");
        if (ttlMs <= 0) throw new IllegalArgumentException("ttlMs must be positive");
        this.cache = cache;
        this.ttlMs = ttlMs;
    }

    public Optional<Product> get(int id) throws IOException {
        if (cache != null) {
            try {
                var value = cache.get(id);
                if (value.isPresent()) {
                    var product = json.readValue(value.orElseThrow(), Product.class);
                    if (product != null && product.id() == id) return Optional.of(product);
                    throw new IOException("cached product id mismatch");
                }
            } catch (IOException | tools.jackson.core.JacksonException failure) {
                cacheFailures.incrementAndGet();
            }
        }
        var product = origin.get(id);
        if (cache != null && product.isPresent()) {
            try {
                cache.put(id, json.writeValueAsString(product.orElseThrow()), ttlMs);
            } catch (IOException | tools.jackson.core.JacksonException failure) {
                cacheFailures.incrementAndGet();
            }
        }
        return product;
    }

    public Optional<Product> update(int id, String name) throws IOException {
        var updated = origin.update(id, name);
        if (updated.isPresent() && cache != null) {
            try {
                cache.delete(id);
            } catch (IOException failure) {
                // 원본 변경을 되돌리지 않는다. TTL이 오래된 캐시의 수명을 제한한다.
                cacheFailures.incrementAndGet();
            }
        }
        return updated;
    }

    public long cacheFailureCount() { return cacheFailures.get(); }
}
