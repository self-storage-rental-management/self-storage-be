package com.storagehub.domain.model;

public enum SystemPermission {
    VIEW_DASHBOARD("view_dashboard"),
    VIEW_FACILITIES("view_facilities"),
    VIEW_UNITS("view_units"),
    BOOK_STORAGE("book_storage"),
    VIEW_RESERVATIONS("view_reservations"),
    APPROVE_RESERVATIONS("approve_reservations"),
    ASSIGN_UNITS("assign_units"),
    VIEW_CONTRACTS("view_contracts"),
    VIEW_CHECKINS("view_checkins"),
    PERFORM_CHECKIN("perform_checkin"),
    VIEW_RENTALS("view_rentals"),
    MANAGE_RENTALS("manage_rentals"),
    VIEW_RETURNS("view_returns"),
    PROCESS_RETURNS("process_returns"),
    VIEW_PAYMENTS("view_payments"),
    VIEW_POLICIES("view_policies"),
    MANAGE_PAYMENTS("manage_payments"),
    VIEW_SUPPORT("view_support"),
    MANAGE_SUPPORT("manage_support"),
    MANAGE_INVENTORY("manage_inventory"),
    MANAGE_POLICIES("manage_policies"),
    MANAGE_STAFF_TASKS("manage_staff_tasks"),
    VIEW_REPORTS("view_reports"),
    VIEW_AUDIT_LOGS("view_audit_logs"),
    MANAGE_USERS("manage_users"),
    MANAGE_ROLES("manage_roles"),
    MANAGE_SETTINGS("manage_settings");

    private final String code;

    SystemPermission(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }
}
