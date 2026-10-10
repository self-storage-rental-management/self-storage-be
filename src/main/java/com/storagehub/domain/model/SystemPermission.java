package com.storagehub.domain.model;

public enum SystemPermission {
    VIEW_DASHBOARD("dashboard:read"),
    VIEW_FACILITIES("facilities:read"),
    VIEW_UNITS("storage_units:read"),
    BOOK_STORAGE("reservations:create"),
    VIEW_RESERVATIONS("reservations:read"),
    APPROVE_RESERVATIONS("reservations:approve"),
    ASSIGN_UNITS("storage_units:assign"),
    VIEW_CONTRACTS("contracts:read"),
    VIEW_CHECKINS("checkins:read"),
    PERFORM_CHECKIN("checkins:process"),
    VIEW_RENTALS("rentals:read"),
    MANAGE_RENTALS("rentals:update"),
    VIEW_RETURNS("returns:read"),
    PROCESS_RETURNS("returns:process"),
    VIEW_PAYMENTS("payments:read"),
    VIEW_POLICIES("policies:read"),
    MANAGE_PAYMENTS("payments:collect"),
    VIEW_SUPPORT("support:read"),
    MANAGE_SUPPORT("support:update"),
    MANAGE_INVENTORY("inventory:update"),
    MANAGE_POLICIES("policies:update"),
    MANAGE_STAFF_TASKS("staff_tasks:update"),
    VIEW_REPORTS("reports:read"),
    VIEW_AUDIT_LOGS("audit_logs:read"),
    MANAGE_USERS("users:manage"),
    MANAGE_ROLES("roles:manage"),
    MANAGE_SETTINGS("settings:manage");

    private final String code;

    SystemPermission(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }
}
