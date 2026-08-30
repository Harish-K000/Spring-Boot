package com.example.platform.inventory.exception;

/** Not enough unreserved stock to satisfy the request. Maps to 409 Conflict. */
public class InsufficientStockException extends RuntimeException {

    private InsufficientStockException(String message) {
        super(message);
    }

    public static InsufficientStockException cannotReserve(String sku, int requested, int available) {
        return new InsufficientStockException(
                "Cannot reserve " + requested + " of " + sku + ": only " + available + " available");
    }

    public static InsufficientStockException belowReserved(String sku, int newQuantity, int reserved) {
        return new InsufficientStockException(
                "Cannot set quantityOnHand of " + sku + " to " + newQuantity
                        + ": " + reserved + " units are already reserved");
    }
}
