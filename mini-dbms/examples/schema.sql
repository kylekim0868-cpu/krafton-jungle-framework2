-- 테이블 준비 (과제 조건상 테이블은 이미 있다고 가정하므로, 처음 한 번만 실행하면 된다)
-- id 컬럼은 자동으로 만들어지고 자동으로 증가한다.
CREATE TABLE IF NOT EXISTS users (name TEXT, age INT, email TEXT);
CREATE TABLE IF NOT EXISTS products (title TEXT, price REAL, stock INT);
