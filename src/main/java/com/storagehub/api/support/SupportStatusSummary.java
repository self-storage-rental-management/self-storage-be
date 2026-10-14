package com.storagehub.api.support;

import java.util.Map;

public record SupportStatusSummary(Map<String, Long> counts, long unassignedOpen) {}
