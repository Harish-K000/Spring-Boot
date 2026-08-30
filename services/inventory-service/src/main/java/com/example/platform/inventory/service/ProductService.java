package com.example.platform.inventory.service;

import com.example.platform.inventory.dto.CreateProductRequest;
import com.example.platform.inventory.dto.PageResponse;
import com.example.platform.inventory.dto.ProductResponse;
import com.example.platform.inventory.dto.UpdateProductRequest;
import com.example.platform.inventory.exception.InsufficientStockException;
import com.example.platform.inventory.exception.InvalidFulfillmentException;
import com.example.platform.inventory.exception.InvalidReleaseException;
import com.example.platform.inventory.exception.InvalidStockQuantityException;
import com.example.platform.inventory.exception.ProductNotFoundException;
import com.example.platform.inventory.exception.ReservedStockException;
import com.example.platform.inventory.exception.SkuAlreadyExistsException;
import com.example.platform.inventory.mapper.ProductMapper;
import com.example.platform.inventory.model.Product;
import com.example.platform.inventory.repository.ProductRepository;
import io.micrometer.observation.annotation.Observed;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;
import java.util.UUID;

@Service
@Transactional(readOnly = true)
public class ProductService {

    private final ProductRepository productRepository;
    private final ProductMapper productMapper;

    public ProductService(ProductRepository productRepository, ProductMapper productMapper) {
        this.productRepository = productRepository;
        this.productMapper = productMapper;
    }

    @Transactional
    @Observed(name = "platform.inventory.create", contextualName = "create-product")
    public ProductResponse create(CreateProductRequest req) {
        String sku = normalizeSku(req.sku());
        if (productRepository.existsBySku(sku)) {
            throw new SkuAlreadyExistsException(sku);
        }
        CreateProductRequest normalized = new CreateProductRequest(
                sku, req.name().trim(), req.quantityOnHand(), req.unitPrice(), req.currency());
        try {
            // Flush inside the transaction so concurrent creates that race on the unique SKU
            // constraint are translated to the API's stable 409 response.
            return productMapper.toResponse(productRepository.saveAndFlush(
                    productMapper.toEntity(normalized)));
        } catch (DataIntegrityViolationException ex) {
            throw new SkuAlreadyExistsException(sku);
        }
    }

    public ProductResponse findById(UUID id) {
        return productMapper.toResponse(getOrThrow(id));
    }

    public ProductResponse findBySku(String sku) {
        String normalizedSku = normalizeSku(sku);
        return productRepository.findBySku(normalizedSku)
                .map(productMapper::toResponse)
                .orElseThrow(() -> new ProductNotFoundException(normalizedSku));
    }

    public PageResponse<ProductResponse> findAll(String name, Pageable pageable) {
        String search = name == null ? null : name.trim();
        Page<Product> page = (search == null || search.isBlank())
                ? productRepository.findAll(pageable)
                : productRepository.findByNameContainingIgnoreCase(search, pageable);
        return PageResponse.from(page, productMapper::toResponse);
    }

    @Transactional
    @Observed(name = "platform.inventory.update", contextualName = "update-product")
    public ProductResponse update(UUID id, UpdateProductRequest req) {
        Product product = getForUpdateOrThrow(id);
        // Physical stock must never drop below what has already been promised to orders.
        if (req.quantityOnHand() < product.getReservedQuantity()) {
            throw InsufficientStockException.belowReserved(
                    product.getSku(), req.quantityOnHand(), product.getReservedQuantity());
        }
        product.setName(req.name().trim());
        product.setQuantityOnHand(req.quantityOnHand());
        if (req.unitPrice() != null) {
            product.setUnitPrice(req.unitPrice());
        }
        if (req.currency() != null) {
            product.setCurrency(req.currency());
        }
        return productMapper.toResponse(productRepository.save(product));
    }

    /** Holds {@code quantity} units for a pending order. Row-locked to prevent overselling. */
    @Transactional
    @Observed(name = "platform.inventory.reserve", contextualName = "reserve-stock")
    public ProductResponse reserve(UUID id, int quantity) {
        requirePositive(quantity);
        Product product = getForUpdateOrThrow(id);
        if (product.availableQuantity() < quantity) {
            throw InsufficientStockException.cannotReserve(
                    product.getSku(), quantity, product.availableQuantity());
        }
        product.setReservedQuantity(product.getReservedQuantity() + quantity);
        return productMapper.toResponse(productRepository.save(product));
    }

    /** Returns previously reserved units to the available pool, e.g. when an order is cancelled. */
    @Transactional
    @Observed(name = "platform.inventory.release", contextualName = "release-stock")
    public ProductResponse release(UUID id, int quantity) {
        requirePositive(quantity);
        Product product = getForUpdateOrThrow(id);
        if (product.getReservedQuantity() < quantity) {
            throw new InvalidReleaseException(product.getSku(), quantity, product.getReservedQuantity());
        }
        product.setReservedQuantity(product.getReservedQuantity() - quantity);
        return productMapper.toResponse(productRepository.save(product));
    }

    /**
     * Completes a reservation after an order is fulfilled. Both the physical and reserved counts
     * decrease, so available stock is unchanged by the transition.
     */
    @Transactional
    @Observed(name = "platform.inventory.fulfill", contextualName = "fulfill-stock")
    public ProductResponse fulfill(UUID id, int quantity) {
        requirePositive(quantity);
        Product product = getForUpdateOrThrow(id);
        if (product.getReservedQuantity() < quantity) {
            throw new InvalidFulfillmentException(
                    product.getSku(), quantity, product.getReservedQuantity());
        }
        product.setReservedQuantity(product.getReservedQuantity() - quantity);
        product.setQuantityOnHand(product.getQuantityOnHand() - quantity);
        return productMapper.toResponse(productRepository.save(product));
    }

    @Transactional
    public void delete(UUID id) {
        Product product = getForUpdateOrThrow(id);
        if (product.getReservedQuantity() > 0) {
            throw new ReservedStockException(product.getSku(), product.getReservedQuantity());
        }
        productRepository.delete(product);
    }

    private Product getOrThrow(UUID id) {
        return productRepository.findById(id).orElseThrow(() -> new ProductNotFoundException(id));
    }

    private Product getForUpdateOrThrow(UUID id) {
        return productRepository.findByIdForUpdate(id).orElseThrow(() -> new ProductNotFoundException(id));
    }

    private String normalizeSku(String sku) {
        return sku.trim().toUpperCase(Locale.ROOT);
    }

    private void requirePositive(int quantity) {
        if (quantity < 1) {
            throw new InvalidStockQuantityException(quantity);
        }
    }
}
