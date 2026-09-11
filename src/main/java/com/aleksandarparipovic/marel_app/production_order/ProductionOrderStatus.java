package com.aleksandarparipovic.marel_app.production_order;

public enum ProductionOrderStatus {
    CREATED,
    DELIVERED,

    /**
     * Called off before delivery. Terminal like DELIVERED, but for the opposite
     * reason: nothing more will happen, and nothing did. The order stays
     * readable — it was written and perhaps partly worked — while every screen
     * stops treating it as open. Set only via cancel(), signed with the
     * caller's password.
     */
    CANCELLED
}
