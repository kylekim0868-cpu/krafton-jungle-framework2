-- Phase 1 데모: INSERT 와 SELECT
INSERT INTO users VALUES ('kim', 25, 'kim@example.com');
INSERT INTO users (name, age) VALUES ('lee', 31), ('park', 19);
INSERT INTO users VALUES ('O''Neil, Jr.', 40, NULL);

SELECT * FROM users;

-- id 조건 → B+ 트리 인덱스 사용 (INDEX_POINT)
SELECT name, email FROM users WHERE id = 2;

-- id 범위 → 인덱스 범위 검색 (INDEX_RANGE)
SELECT id, name FROM users WHERE id BETWEEN 2 AND 3;

-- 다른 컬럼 조건 → 선형 탐색 (FULL_SCAN)
SELECT * FROM users WHERE age >= 25 AND email IS NOT NULL;

SELECT COUNT(*) FROM users WHERE age < 30;

INSERT INTO products VALUES ('keyboard', 49.9, 10), ('mouse', 19, 0);
SELECT title, price FROM products WHERE price < 30 LIMIT 5;
