-- ==============================================================================
-- MODULE 2: FACILITIES, UNIT TYPES & STORAGE UNITS (CƠ SỞ & Ô KHO)
-- ==============================================================================

CREATE TABLE facilities (
    id VARCHAR(36) NOT NULL,
    code VARCHAR(32) NOT NULL,
    name VARCHAR(160) NOT NULL,
    address VARCHAR(255) NOT NULL,
    city VARCHAR(100) NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE (code)
);

CREATE TABLE unit_types (
    id VARCHAR(36) NOT NULL,
    facility_id VARCHAR(36) NOT NULL,
    code VARCHAR(20) NOT NULL,
    name VARCHAR(100) NOT NULL,
    length_m DECIMAL(10,2) NOT NULL,
    width_m DECIMAL(10,2) NOT NULL,
    height_m DECIMAL(10,2) NOT NULL,
    monthly_price DECIMAL(14,2) NOT NULL,
    image_url VARCHAR(1000) NULL,
    max_load_kg DECIMAL(10,2) NULL,
    rack_count INT NULL,
    rack_length_m DECIMAL(10,2) NULL,
    rack_width_m DECIMAL(10,2) NULL,
    rack_height_m DECIMAL(10,2) NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE (facility_id, code),
    CONSTRAINT fk_unit_types_facility FOREIGN KEY (facility_id) REFERENCES facilities(id) ON DELETE CASCADE
);

CREATE TABLE storage_units (
    id VARCHAR(36) NOT NULL,
    facility_id VARCHAR(36) NOT NULL,
    unit_type_id VARCHAR(36) NOT NULL,
    code VARCHAR(64) NOT NULL,
    floor VARCHAR(32) NULL,
    zone VARCHAR(32) NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'AVAILABLE',
    available_from TIMESTAMP NULL,
    last_released_at TIMESTAMP NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE (code),
    CONSTRAINT fk_storage_units_facility FOREIGN KEY (facility_id) REFERENCES facilities(id) ON DELETE CASCADE,
    CONSTRAINT fk_storage_units_type FOREIGN KEY (unit_type_id) REFERENCES unit_types(id) ON DELETE RESTRICT
);

CREATE TABLE rental_package_policies (
    id VARCHAR(36) NOT NULL,
    facility_id VARCHAR(36) NULL,
    duration_months INT NOT NULL,
    discount_percent DECIMAL(5,2) NOT NULL,
    renewal_discount_percent DECIMAL(5,2) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    CONSTRAINT fk_package_policies_facility FOREIGN KEY (facility_id) REFERENCES facilities(id) ON DELETE CASCADE
);
