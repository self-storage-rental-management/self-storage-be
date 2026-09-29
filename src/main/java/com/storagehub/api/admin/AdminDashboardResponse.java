package com.storagehub.api.admin;

import com.storagehub.domain.model.RoleCode;
import java.util.List;
import java.util.Map;

public record AdminDashboardResponse(
    long totalUsers,
    Map<RoleCode, Long> usersByRole,
    long lockedAccountsCount,
    long disabledAccountsCount,
    List<AdminLoginHistoryResponse> recentLoginFailures,
    List<AdminActivityLogResponse> recentSecurityEvents
) {
}
