package com.storagehub.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "reservation_code_sequences")
@Getter
@Setter
@NoArgsConstructor
public class ReservationCodeSequence {

    @Id
    private Integer id;

    @Column(name = "next_value", nullable = false)
    private long nextValue;
}
