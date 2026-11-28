package com.storagehub.api.configuration;

public record PublicSiteConfigResponse(
    boolean maintenanceMode,
    String bannerNotice
) {
}
