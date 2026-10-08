package com.storagehub.service.renewal.persistence;

import com.storagehub.domain.model.*;
import jakarta.persistence.*;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity @Table(name="renewal_open_slots") @Getter @NoArgsConstructor(access=lombok.AccessLevel.PROTECTED)
public class RenewalOpenSlot {
    @Id private UUID id;
    @OneToOne(fetch=FetchType.LAZY,optional=false) @MapsId @JoinColumn(name="id") private Rental rental;
    @OneToOne(fetch=FetchType.LAZY,optional=false) @JoinColumn(nullable=false,unique=true) private Renewal renewal;
    public RenewalOpenSlot(Rental rental,Renewal renewal) {this.rental=rental;this.renewal=renewal;}
}
