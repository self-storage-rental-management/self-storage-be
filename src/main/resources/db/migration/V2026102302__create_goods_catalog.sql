CREATE TABLE goods_type_catalog (
    id BINARY(16) NOT NULL,
    code VARCHAR(40) NOT NULL,
    display_name VARCHAR(160) NOT NULL,
    status VARCHAR(16) NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_goods_type_catalog_code UNIQUE (code)
);

CREATE TABLE goods_material_catalog (
    id BINARY(16) NOT NULL,
    goods_type_id BINARY(16) NOT NULL,
    code VARCHAR(24) NOT NULL,
    display_name VARCHAR(160) NOT NULL,
    fragile BOOLEAN NOT NULL,
    status VARCHAR(16) NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_goods_material_catalog_code UNIQUE (code),
    CONSTRAINT fk_goods_material_catalog_type FOREIGN KEY (goods_type_id) REFERENCES goods_type_catalog(id)
);

CREATE INDEX idx_goods_material_catalog_type_status
    ON goods_material_catalog(goods_type_id, status);
