package com.insureflow.estimator;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

@Component
public class CarPartsPriceSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(CarPartsPriceSeeder.class);

    private static final String INSERT_SQL =
            "INSERT INTO car_parts_prices (car, model, year, body_part, shop1_price, shop2_price, shop3_price, source) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?, ?)";

    private final JdbcTemplate jdbc;

    public CarPartsPriceSeeder(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM car_parts_prices", Integer.class);
        if (count != null && count > 0) {
            log.info("[SEEDER] car_parts_prices already has {} rows — skipping seed", count);
            return;
        }

        ClassPathResource csv = new ClassPathResource("data/insureflow_parts_dataset.csv");
        List<Object[]> rows = new ArrayList<>();

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(csv.getInputStream()))) {
            String header = reader.readLine(); // skip CSV header
            if (header == null) return;        // empty file guard

            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) continue;

                String[] cols = parseCsvLine(line);
                if (cols.length < 8) continue;

                String     car      = normalize(cols[0]);
                String     model    = normalize(cols[1]);
                Integer    year     = parseYear(cols[2]);
                String     bodyPart = normalize(cols[3]);
                BigDecimal shop1    = parsePrice(cols[4]);   // nullable
                BigDecimal shop2    = parsePrice(cols[5]);   // NOT NULL in schema
                BigDecimal shop3    = parsePrice(cols[6]);   // NOT NULL in schema
                String     source   = normalize(cols[7]);

                // Skip rows that would violate NOT NULL constraints
                if (car.isEmpty() || bodyPart.isEmpty() || shop2 == null || shop3 == null) continue;

                rows.add(new Object[]{car, model.isEmpty() ? null : model, year,
                                      bodyPart, shop1, shop2, shop3,
                                      source.isEmpty() ? null : source});
            }
        }

        jdbc.batchUpdate(INSERT_SQL, rows);
        log.info("[SEEDER] Inserted {} rows into car_parts_prices", rows.size());
    }

    /**
     * Minimal RFC-4180-compliant CSV parser: handles quoted fields with embedded commas.
     * Does not support multi-line fields (none exist in this dataset).
     */
    private String[] parseCsvLine(String line) {
        List<String> fields = new ArrayList<>();
        StringBuilder sb = new StringBuilder();
        boolean inQuotes = false;

        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') {
                inQuotes = !inQuotes;
            } else if (c == ',' && !inQuotes) {
                fields.add(sb.toString());
                sb.setLength(0);
            } else {
                sb.append(c);
            }
        }
        fields.add(sb.toString());
        return fields.toArray(new String[0]);
    }

    private String normalize(String s) {
        return s == null ? "" : s.trim().toLowerCase();
    }

    private Integer parseYear(String s) {
        if (s == null) return null;
        try { return Integer.parseInt(s.trim()); }
        catch (NumberFormatException e) { return null; }
    }

    /**
     * Parses a price field, handling:
     *   - empty / "null" strings → null
     *   - European decimal comma: "1717,95" → 1717.95
     *   - US thousands separator: "2,286.48" → 2286.48
     */
    private BigDecimal parsePrice(String s) {
        if (s == null) return null;
        String clean = s.trim();
        if (clean.isEmpty() || clean.equalsIgnoreCase("null")) return null;

        if (clean.contains(".")) {
            // Period is the decimal separator — commas are thousands separators
            clean = clean.replace(",", "");
        } else if (clean.contains(",")) {
            // Comma is the decimal separator (European format)
            clean = clean.replace(",", ".");
        }

        try { return new BigDecimal(clean); }
        catch (NumberFormatException e) { return null; }
    }
}
