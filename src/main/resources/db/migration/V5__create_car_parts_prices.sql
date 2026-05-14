CREATE TABLE car_parts_prices (
    id          BIGSERIAL PRIMARY KEY,
    car         VARCHAR(50)   NOT NULL,
    model       VARCHAR(50),
    year        INTEGER,
    body_part   VARCHAR(100)  NOT NULL,
    shop1_price NUMERIC(10,2),
    shop2_price NUMERIC(10,2) NOT NULL,
    shop3_price NUMERIC(10,2) NOT NULL,
    source      VARCHAR(20),
    created_at  TIMESTAMP DEFAULT NOW()
);

-- Fast lookup by car + year + part (exact match)
CREATE INDEX idx_cpp_car_year_part ON car_parts_prices (car, year, body_part);
-- Fallback: car + part ignoring year
CREATE INDEX idx_cpp_car_part      ON car_parts_prices (car, body_part);
-- Fallback: part-only average across all cars
CREATE INDEX idx_cpp_body_part     ON car_parts_prices (body_part);
