package com.storagehub.service;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Configurable setup & inspection policy including baseline proposals P01-P09.
 * Proposals P01-P09 remain configurable so production approval can adjust or disable
 * any proposal without rewriting core state transitions.
 */
@Component
@ConfigurationProperties(prefix = "storagehub.intake")
@Getter
@Setter
public class IntakePolicyProperties {

    public static final String POLICY_VERSION = "INTAKE_UNIFIED_V2";

    private int setupCutoffHours = 6;
    private int completionWindowHours = 72;
    private int maxExtensions = 2;
    private int arrivalReminderHoursBefore = 48;
    private int missedReminderIntervalHours = 2;

    /** P01: fixed 30-minute reconciliation window after D (0 disables auto-window). */
    private int attendanceReviewMinutes = 30;
    /** P02: fail inspection immediately on 2nd substantive verification FAIL. */
    private boolean immediateFailOnSecondVerificationFailure = true;
    /** P03: wait window at appointment before Staff may mark MISSED. */
    private int missedWaitMinutes = 30;
    /** P04: SLA escalation thresholds (minutes). */
    private int attendanceStaffReminderMinutes = 15;
    private int attendanceManagerEscalationMinutes = 30;
    private int attendanceBoAlertMinutes = 120;
    /** P05: reminders before F (hours). */
    private int completionReminderFirstHoursBefore = 24;
    private int completionReminderSecondHoursBefore = 6;
    /** P06: require whole positive days (24h increments) for customer extensions. */
    private boolean requireWholeDayExtensions = true;
    /** P07: auto-approve eligible extensions. */
    private boolean autoApproveExtensions = true;
    /** P08: technical verification errors do not consume attempt quota. */
    private boolean protectAttemptsOnTechnicalError = true;
    /** P09: facility errors route to Manager/BO without auto-recovery. */
    private boolean protectFromAutoRecoveryOnFacilityException = true;
    /** Slot capacity per facility per appointment hour (0 = unlimited). */
    private int appointmentSlotCapacity = 3;

    private Map<String, SizeRule> sizeRules = defaultSizeRules();

    public SizeRule ruleFor(String unitTypeCode) {
        if (unitTypeCode == null || unitTypeCode.isBlank()) return null;
        String key = unitTypeCode.trim().toUpperCase(Locale.ROOT);
        SizeRule rule = sizeRules.get(key);
        if (rule == null) {
            rule = defaultSizeRules().get(key);
        }
        if (rule == null) {
            rule = defaultSizeRules().get("M");
        }
        return rule;
    }

    private static Map<String, SizeRule> defaultSizeRules() {
        Map<String, SizeRule> defaults = new LinkedHashMap<>();
        defaults.put("S", new SizeRule(120, 48));
        defaults.put("M", new SizeRule(168, 72));
        defaults.put("L", new SizeRule(216, 96));
        defaults.put("XL", new SizeRule(288, 120));
        return defaults;
    }

    @Getter
    @Setter
    public static class SizeRule {
        private int initialArrivalHours;
        private int maxExtensionHours;

        public SizeRule() {}

        public SizeRule(int initialArrivalHours, int maxExtensionHours) {
            this.initialArrivalHours = initialArrivalHours;
            this.maxExtensionHours = maxExtensionHours;
        }
    }
}
