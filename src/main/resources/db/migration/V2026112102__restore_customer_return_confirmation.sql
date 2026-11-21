-- Restore unfinished return settlements that were confirmed through the removed
-- staff/manager quick-settlement shortcut. The customer must make the decision.
UPDATE return_cases
SET status = 'awaiting_customer_confirmation',
    customer_confirmed = FALSE,
    customer_confirmed_at = NULL,
    customer_decision = NULL,
    customer_decision_note = NULL,
    refund_expected_at = NULL,
    updated_at = CURRENT_TIMESTAMP(6)
WHERE status IN ('refund_pending', 'payment_due')
  AND settlement_paid_at IS NULL
  AND settlement_payment_id IS NULL
  AND customer_decision_note IN (
      'Nhân viên hỗ trợ xác nhận quyết toán trực tiếp tại quầy',
      'Nhân viên xác nhận quyết toán trả kho nhanh trực tiếp tại quầy',
      'Quản lý duyệt quyết toán trực tiếp tại quầy',
      'Xác nhận quyết toán trực tiếp tại quầy bởi nhân viên cơ sở.'
  );
