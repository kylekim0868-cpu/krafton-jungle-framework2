package minidbms;

/** products의 고정 스키마. price는 최소 화폐 단위의 0 이상 정수다. */
public record Product(long id, String name, long price) {
    public Product {
        if (id <= 0 || name == null || price < 0) throw new IllegalArgumentException("invalid product");
    }
}
