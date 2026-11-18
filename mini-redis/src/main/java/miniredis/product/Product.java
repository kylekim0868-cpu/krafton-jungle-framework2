package miniredis.product;

/** 예제 원본 레코드. DBMS 연동은 범위 밖이다. */
public record Product(int id, String name) {
    public Product {
        if (id <= 0 || name == null) throw new IllegalArgumentException("invalid product");
    }
}
