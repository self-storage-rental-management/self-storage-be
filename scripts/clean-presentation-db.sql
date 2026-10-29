-- StorageHub local presentation reset.
-- Destructive: use only with the local storagehub_local database.
-- Keeps schema/Flyway history, catalog, roles/permissions and system settings.
-- Run scripts/seed-demo-data.sql immediately after this script.

SET FOREIGN_KEY_CHECKS = 0;
START TRANSACTION;

-- Authentication/session noise and uploaded demo artifacts.
DELETE FROM auth_challenges;
DELETE FROM sessions;
DELETE FROM login_history;
DELETE FROM customer_chat_sessions;
DELETE FROM file_assets;

-- Reservation, rental, payment, check-in and return transactions.
DELETE FROM return_cases;
DELETE FROM return_financial_snapshot_history;
DELETE FROM return_financial_snapshots;
DELETE FROM return_ledger_allocations;
DELETE FROM return_settlement_receipts;
DELETE FROM damage_incidents;
DELETE FROM payments;
DELETE FROM refunds;
DELETE FROM refund_requests;
DELETE FROM payment_complaints;
DELETE FROM overdue_follow_up_states;
DELETE FROM overdue_follow_ups;
DELETE FROM recovery_overdue_fee_assessments;
DELETE FROM check_ins;
DELETE FROM confirmed_rental_periods;
DELETE FROM contracts;
DELETE FROM renewals;
DELETE FROM renewal_workflows;
DELETE FROM renewal_quotes;
DELETE FROM renewal_open_slots;
DELETE FROM renewal_operation_states;
DELETE FROM renewal_operation_events;
DELETE FROM renewal_signing_reservations;
DELETE FROM renewal_calendar_staff_locks;
DELETE FROM renewal_accepted_revisions;
DELETE FROM renewal_idempotency;
DELETE FROM renewal_extension_holds;
DELETE FROM renewal_installment_notices;
DELETE FROM renewal_installment_payment_bindings;
DELETE FROM renewal_installment_payment_quotes;
DELETE FROM renewal_ledger_cancellation_credits;
DELETE FROM rental_ledger_refunds;
DELETE FROM rental_ledger_receipts;
DELETE FROM rental_ledger_obligations;
DELETE FROM rental_ledger_commands;
DELETE FROM rental_ledger_due_changes;
DELETE FROM rental_ledger_allocations;
DELETE FROM rental_ledger_accounts;
DELETE FROM rentals;
DELETE FROM reservation_email_verifications;
DELETE FROM reservation_goods_items;
DELETE FROM reservation_pricing_snapshots;
DELETE FROM reservation_quotes;
DELETE FROM reservations;
DELETE FROM booking_documents;
DELETE FROM access_credentials;

-- Operational/support noise.
DELETE FROM maintenance_tasks;
DELETE FROM unit_assignments;
DELETE FROM unit_type_change_requests;
DELETE FROM support_message_reads;
DELETE FROM support_messages;
DELETE FROM support_ticket_messages;
DELETE FROM support_escalations;
DELETE FROM support_workflow_events;
DELETE FROM support_workflow_states;
DELETE FROM support_command_receipts;
DELETE FROM support_hidden_history;
DELETE FROM support_tickets;
DELETE FROM notifications;
DELETE FROM notification_outbox;
DELETE FROM activity_logs;

-- Reset generated business-code state.
DELETE FROM business_record_codes;
DELETE FROM business_record_code_sequences;
DELETE FROM reservation_code_sequences;

-- Rebuild demo inventory and policies from the canonical seed file.
DELETE FROM storage_units;
DELETE FROM unit_types;
DELETE FROM rental_package_policies;
DELETE FROM discount_codes;
DELETE FROM facility_policy_catalog;
DELETE FROM user_facility_scopes;
DELETE FROM facilities;

-- Remove accounts created during testing, while preserving the five presentation accounts.
DELETE ur
FROM user_roles ur
JOIN users u ON u.id = ur.user_id
WHERE u.email NOT IN (
  'admin@storagehub.demo',
  'business@storagehub.demo',
  'manager@storagehub.demo',
  'staff@storagehub.demo',
  'customer@storagehub.demo'
);
DELETE FROM users
WHERE email NOT IN (
  'admin@storagehub.demo',
  'business@storagehub.demo',
  'manager@storagehub.demo',
  'staff@storagehub.demo',
  'customer@storagehub.demo'
);

-- Remove profile values that may have been changed during testing.
UPDATE users
SET avatar_url = NULL,
    emergency_contact_name = NULL,
    emergency_contact_phone = NULL,
    permanent_address = NULL,
    authz_version = 0
WHERE email IN (
  'admin@storagehub.demo',
  'business@storagehub.demo',
  'manager@storagehub.demo',
  'staff@storagehub.demo',
  'customer@storagehub.demo'
);

-- First reservation code after reset is RES-...-0001.
INSERT INTO reservation_code_sequences (id, next_value) VALUES (1, 1);

COMMIT;
SET FOREIGN_KEY_CHECKS = 1;
