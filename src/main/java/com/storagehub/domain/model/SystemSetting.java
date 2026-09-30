package com.storagehub.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "system_settings")
@Getter
@Setter
@NoArgsConstructor
public class SystemSetting extends BaseEntity {

    @Column(nullable = false, unique = true, length = 100)
    private String settingKey;

    @Column(nullable = false, length = 100)
    private String groupName;

    @Column(length = 500)
    private String description;

    @Column(nullable = false, length = 160)
    private String label;

    @Column(nullable = false, length = 20)
    private String settingType;

    @Column(name = "setting_value", nullable = false, length = 2000)
    private String value;

    @Column(length = 4000)
    private String optionsJson;
}
