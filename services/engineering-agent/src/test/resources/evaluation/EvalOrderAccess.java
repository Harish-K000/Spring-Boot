import java.util.Optional;
record Order(String id, String ownerId) {}
interface OrderRepository { Optional<Order> findById(String id); }
class EvalOrderAccess {
    OrderRepository repository;
    Order view(String requesterId, String orderId) {
        Order order = repository.findById(orderId).orElseThrow();
        return order;
    }
}
