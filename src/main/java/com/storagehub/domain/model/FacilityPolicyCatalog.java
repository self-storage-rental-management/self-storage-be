package com.storagehub.domain.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

@Entity @Table(name = "facility_policy_catalog") @Getter @Setter
public class FacilityPolicyCatalog {
    @Id private String id;
    @Column(nullable = false, columnDefinition = "LONGTEXT") private String contentJson;
    @Version private long version;
}
