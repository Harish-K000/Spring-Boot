package com.example.platform.inventory.mapper;

import com.example.platform.inventory.dto.CreateProductRequest;
import com.example.platform.inventory.dto.ProductResponse;
import com.example.platform.inventory.model.Product;
import org.springframework.stereotype.Component;

@Component
public class ProductMapper {

    public Product toEntity(CreateProductRequest req) {
        Product product = new Product();
        product.setSku(req.sku());
        product.setName(req.name());
        product.setQuantityOnHand(req.quantityOnHand());
        product.setReservedQuantity(0);
        product.setUnitPrice(req.unitPrice());
        product.setCurrency(req.currency());
        return product;
    }

    public ProductResponse toResponse(Product product) {
        return new ProductResponse(
                product.getId(),
                product.getSku(),
                product.getName(),
                product.getQuantityOnHand(),
                product.getReservedQuantity(),
                product.availableQuantity(),
                product.getUnitPrice(),
                product.getCurrency(),
                product.getCreatedAt(),
                product.getUpdatedAt());
    }
}
