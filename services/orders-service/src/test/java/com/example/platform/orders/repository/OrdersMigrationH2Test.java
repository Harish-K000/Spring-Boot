package com.example.platform.orders.repository;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Always exercises the production Flyway scripts and Hibernate validation without Docker. */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:ordersmigrations;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.jpa.properties.hibernate.default_schema=orders",
        "spring.flyway.enabled=true",
        "spring.flyway.create-schemas=true",
        "spring.flyway.schemas=orders",
        "spring.flyway.default-schema=orders"
})
class OrdersMigrationH2Test {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void migrationsMatchMappingsAndRejectInvalidLineQuantity() {
        UUID orderId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO orders.orders
                    (id, user_id, status, total_amount, currency)
                VALUES (?, ?, ?, ?, ?)
                """, orderId, UUID.randomUUID(), "PENDING", BigDecimal.TEN, "USD");

        assertThatThrownBy(() -> jdbcTemplate.update("""
                        INSERT INTO orders.order_items
                            (id, order_id, product_id, sku, product_name,
                             quantity, unit_price, line_total)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                        """, UUID.randomUUID(), orderId, UUID.randomUUID(), "SKU", "Product",
                        0, BigDecimal.ONE, BigDecimal.ZERO))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
