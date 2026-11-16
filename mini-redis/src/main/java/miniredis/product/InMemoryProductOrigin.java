package miniredis.product;

import java.io.IOException;
import java.util.Collection;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/** 재현 가능한 테스트 원본. 지연과 호출 수는 실험용이며 실제 DB 성능을 나타내지 않는다. */
public final class InMemoryProductOrigin implements ProductOrigin {
    private final ConcurrentHashMap<Integer, Product> products = new ConcurrentHashMap<>();
    private final AtomicLong reads = new AtomicLong();
    private final long delayMs;

    public InMemoryProductOrigin(Collection<Product> initial, long delayMs) {
        if (delayMs < 0) throw new IllegalArgumentException("delay must not be negative");
        initial.forEach(product -> products.put(product.id(), product));
        this.delayMs = delayMs;
    }

    @Override public Optional<Product> get(int id) throws IOException {
        reads.incrementAndGet();
        try {
            Thread.sleep(delayMs);
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new IOException("origin read interrupted", failure);
        }
        return Optional.ofNullable(products.get(id));
    }

    @Override public Optional<Product> update(int id, String name) {
        return Optional.ofNullable(products.computeIfPresent(id, (key, old) -> new Product(key, name)));
    }

    public long readCount() { return reads.get(); }
}
