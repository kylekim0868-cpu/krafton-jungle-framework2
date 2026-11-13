package miniredis.product;

import java.io.IOException;
import java.util.Optional;

public interface ProductOrigin {
    Optional<Product> get(int id) throws IOException;
    Optional<Product> update(int id, String name) throws IOException;
}
