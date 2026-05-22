-- SERIAL creates an INTEGER column; Hibernate maps Long @Id to BIGINT.
-- Widen the id column so schema-validation passes.
ALTER TABLE non_vehicle_prices ALTER COLUMN id TYPE BIGINT;
