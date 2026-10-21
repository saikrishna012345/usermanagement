package com.blackroth.training.mobilebackend.event.contract;

public final class EventTypes {

    private EventTypes() {}

    public static final String ORDER_CREATED = "ORDER_CREATED";
    public static final String ORDER_CANCELLED = "ORDER_CANCELLED";
    public static final String ORDER_SHIPPED = "ORDER_SHIPPED";
    public static final String ORDER_DELIVERED = "ORDER_DELIVERED";
    public static final String INVENTORY_UPDATED = "INVENTORY_UPDATED";
    public static final String NOTIFICATION_REQUESTED = "NOTIFICATION_REQUESTED";

    public static boolean isKnownEventType(String eventType) {
        if (eventType == null || eventType.isBlank()) {
            return false;
        }
        return switch (eventType) {
            case ORDER_CREATED, ORDER_CANCELLED, ORDER_SHIPPED, ORDER_DELIVERED,
                 INVENTORY_UPDATED, NOTIFICATION_REQUESTED -> true;
            default -> false;
        };
    }
}
