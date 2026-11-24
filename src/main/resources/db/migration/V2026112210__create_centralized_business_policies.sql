CREATE TABLE IF NOT EXISTS centralized_business_policies (
    id BINARY(16) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    policy_version VARCHAR(50) NOT NULL,
    publication_status VARCHAR(20) NOT NULL,
    published_at TIMESTAMP(6) NULL,
    published_by BINARY(16) NULL,
    effective_from DATE NOT NULL,
    effective_to DATE NULL,
    active BOOLEAN NOT NULL DEFAULT FALSE,
    change_summary TEXT NULL,

    -- RULE 1 & 2: Mandatory Security Deposit & Calculation
    deposit_mandatory BOOLEAN NOT NULL DEFAULT TRUE,
    deposit_calculation_method VARCHAR(32) NOT NULL DEFAULT 'MONTHLY_RENT_MULTIPLE',
    deposit_monthly_multiple DECIMAL(5, 2) NOT NULL DEFAULT 1.00,
    deposit_fixed_amount DECIMAL(14, 2) NOT NULL DEFAULT 2000000.00,
    deposit_percentage_rate DECIMAL(5, 4) NOT NULL DEFAULT 0.2000,
    minimum_deposit_amount DECIMAL(14, 2) NOT NULL DEFAULT 500000.00,

    -- RULE 3: Refund Conditions
    full_refund_conditions TEXT NULL,
    partial_refund_conditions TEXT NULL,
    no_refund_conditions TEXT NULL,
    refund_processing_days INT NOT NULL DEFAULT 7,

    -- RULE 4 & 8: Early Rental Renewal / Extension
    allow_early_renewal BOOLEAN NOT NULL DEFAULT TRUE,
    available_extension_periods VARCHAR(100) NOT NULL DEFAULT '1,3,6,12,24',
    renewal_advance_notice_days INT NOT NULL DEFAULT 15,
    require_storage_availability_check BOOLEAN NOT NULL DEFAULT TRUE,
    renewal_discount_rate DECIMAL(5, 4) NOT NULL DEFAULT 0.0500,

    -- RULE 5: Early Contract Termination
    allow_early_termination BOOLEAN NOT NULL DEFAULT TRUE,
    early_termination_notice_days INT NOT NULL DEFAULT 14,
    early_termination_fee_coefficient DECIMAL(5, 4) NOT NULL DEFAULT 1.0000,
    early_termination_fee_cap_months INT NOT NULL DEFAULT 1,
    early_termination_min_fee DECIMAL(14, 2) NOT NULL DEFAULT 500000.00,

    -- RULE 6: Late Payment Penalty
    grace_period_days INT NOT NULL DEFAULT 3,
    late_penalty_calculation_method VARCHAR(32) NOT NULL DEFAULT 'FIXED_DAILY_AMOUNT',
    late_fee_amount DECIMAL(14, 2) NOT NULL DEFAULT 50000.00,
    late_fee_percentage_rate DECIMAL(5, 4) NOT NULL DEFAULT 0.0050,
    max_overdue_grace_days_before_repossession INT NOT NULL DEFAULT 7,

    -- RULE 7: Security Deposit Deduction
    deduction_categories TEXT NULL,
    require_evidence_for_deduction BOOLEAN NOT NULL DEFAULT TRUE,
    require_manager_approval_for_deduction BOOLEAN NOT NULL DEFAULT TRUE,
    exclude_normal_wear_and_tear BOOLEAN NOT NULL DEFAULT TRUE,

    -- RULE 9: Pricing Protection
    pricing_protection_enforced BOOLEAN NOT NULL DEFAULT TRUE,
    pricing_version VARCHAR(50) NOT NULL DEFAULT 'PRICE-2026.1',

    PRIMARY KEY (id),
    UNIQUE KEY uk_centralized_policy_version (policy_version),
    CONSTRAINT fk_centralized_policy_published_by FOREIGN KEY (published_by) REFERENCES users(id) ON DELETE SET NULL,
    INDEX ix_centralized_policy_status_active (publication_status, active),
    INDEX ix_centralized_policy_effective_window (effective_from, effective_to)
) ENGINE=InnoDB;
