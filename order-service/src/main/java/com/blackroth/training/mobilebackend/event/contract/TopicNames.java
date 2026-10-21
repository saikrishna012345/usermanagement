package com.blackroth.training.mobilebackend.event.contract;

public final class TopicNames {

    private TopicNames() {}

    public static final String ORDER_CREATED = "order.created";
    public static final String ORDER_CANCELLED = "order.cancelled";
    public static final String ORDER_SHIPPED = "order.shipped";
    public static final String ORDER_DELIVERED = "order.delivered";
    public static final String INVENTORY_UPDATED = "inventory.updated";
    public static final String NOTIFICATION_REQUESTED = "notification.requested";

    // Dead Letter Topics
    public static final String ORDER_CREATED_DLT = "order.created.dlt";
    public static final String ORDER_CANCELLED_DLT = "order.cancelled.dlt";
    public static final String ORDER_SHIPPED_DLT = "order.shipped.dlt";
    public static final String ORDER_DELIVERED_DLT = "order.delivered.dlt";
    public static final String INVENTORY_UPDATED_DLT = "inventory.updated.dlt";
    public static final String NOTIFICATION_REQUESTED_DLT = "notification.requested.dlt";
}
