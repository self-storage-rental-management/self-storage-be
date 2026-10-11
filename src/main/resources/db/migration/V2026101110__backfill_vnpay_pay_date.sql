UPDATE payments
SET gateway_pay_date = DATE_FORMAT(DATE_ADD(paid_at, INTERVAL 7 HOUR), '%Y%m%d%H%i%s')
WHERE gateway_provider = 'VNPAY'
  AND status = 'PAID'
  AND gateway_pay_date IS NULL
  AND paid_at IS NOT NULL;
