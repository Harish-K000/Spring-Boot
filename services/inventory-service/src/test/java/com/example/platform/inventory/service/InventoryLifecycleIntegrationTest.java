package com.example.platform.inventory.service;

import com.example.platform.inventory.dto.CreateProductRequest;
import com.example.platform.inventory.dto.ProductResponse;
import com.example.platform.inventory.repository.ProductRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

/** Exercises the complete stock lifecycle through real service transactions and JPA. */
@SpringBootTest
class InventoryLifecycleIntegrationTest {

    @Autowired
    private ProductService productService;

    @Autowired
    private ProductRepository productRepository;

    @Test
    void reservationCanBePartiallyFulfilledThenReleased() {
        ProductResponse created = productService.create(
                new CreateProductRequest(" lifecycle-1 ", " Test product ", 10));

        ProductResponse reserved = productService.reserve(created.id(), 4);
        ProductResponse fulfilled = productService.fulfill(created.id(), 2);
        ProductResponse released = productService.release(created.id(), 2);

        assertThat(reserved.availableQuantity()).isEqualTo(6);
        assertThat(fulfilled.quantityOnHand()).isEqualTo(8);
        assertThat(fulfilled.reservedQuantity()).isEqualTo(2);
        assertThat(fulfilled.availableQuantity()).isEqualTo(6);
        assertThat(released.reservedQuantity()).isZero();
        assertThat(released.availableQuantity()).isEqualTo(8);
        assertThat(productService.findBySku(" lifecycle-1 ").id()).isEqualTo(created.id());

        productService.delete(created.id());
        assertThat(productRepository.existsById(created.id())).isFalse();
    }
}
