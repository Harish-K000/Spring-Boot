package com.example.platform.inventory.repository;

import com.example.platform.inventory.model.Product;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
class ProductRepositoryTest {

    @Autowired
    private ProductRepository productRepository;

    private Product persist(String sku, String name, int onHand, int reserved) {
        Product product = new Product();
        product.setSku(sku);
        product.setName(name);
        product.setQuantityOnHand(onHand);
        product.setReservedQuantity(reserved);
        return productRepository.saveAndFlush(product);
    }

    @Test
    void findBySkuLocatesProduct() {
        persist("SKU-1", "Widget", 5, 0);

        assertThat(productRepository.findBySku("SKU-1")).isPresent();
        assertThat(productRepository.findBySku("SKU-MISSING")).isEmpty();
    }

    @Test
    void skuUniquenessIsEnforced() {
        persist("SKU-DUP", "Widget", 5, 0);

        assertThatThrownBy(() -> persist("SKU-DUP", "Other", 1, 0))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void existsBySkuReflectsPersistedState() {
        persist("SKU-2", "Widget", 5, 0);

        assertThat(productRepository.existsBySku("SKU-2")).isTrue();
        assertThat(productRepository.existsBySku("SKU-3")).isFalse();
    }

    @Test
    void findByNameContainingIsCaseInsensitive() {
        persist("SKU-A", "Blue Widget", 1, 0);
        persist("SKU-B", "Red Gadget", 1, 0);

        assertThat(productRepository.findByNameContainingIgnoreCase("widget", PageRequest.of(0, 10))
                .getTotalElements()).isEqualTo(1);
        assertThat(productRepository.findByNameContainingIgnoreCase("e", PageRequest.of(0, 10))
                .getTotalElements()).isEqualTo(2);
    }

    @Test
    void findByIdForUpdateReturnsTheRow() {
        Product saved = persist("SKU-LOCK", "Widget", 9, 2);

        Product locked = productRepository.findByIdForUpdate(saved.getId()).orElseThrow();

        assertThat(locked.getSku()).isEqualTo("SKU-LOCK");
        assertThat(locked.availableQuantity()).isEqualTo(7);
    }

    @Test
    void availableQuantityIsDerivedFromPersistedCounts() {
        Product saved = persist("SKU-AVAIL", "Widget", 10, 4);

        assertThat(productRepository.findById(saved.getId()).orElseThrow().availableQuantity())
                .isEqualTo(6);
    }
}
