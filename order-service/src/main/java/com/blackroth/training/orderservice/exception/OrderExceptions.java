package com.blackroth.training.orderservice.exception;

public class OrderExceptions {
    private OrderExceptions() {

    }

    public static class OrderNotFound extends RuntimeException {
        public OrderNotFound(String m) {
            super(m);
        }
    }

    public static class ProductNotFound extends RuntimeException {
        public ProductNotFound(String m) {
            super(m);
        }
    }

    public static class ProductServiceUnavailable extends RuntimeException {
        public ProductServiceUnavailable(String m, Throwable c) {
            super(m, c);
        }

        public ProductServiceUnavailable(String m) {
            super(m);
        }
    }

    public static class ProductServiceTimeout extends RuntimeException {
        public ProductServiceTimeout(String m, Throwable c) {
            super(m, c);
        }
    }

    public static class InsufficientStock extends RuntimeException {
        public InsufficientStock(String m) {
            super(m);
        }
    }
}
