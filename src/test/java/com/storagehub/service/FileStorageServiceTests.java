package com.storagehub.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.storagehub.config.FileProperties;
import com.storagehub.domain.model.Reservation;
import com.storagehub.domain.model.ReservationGoodsItem;
import com.storagehub.domain.model.RoleCode;
import com.storagehub.domain.model.User;
import com.storagehub.domain.repo.FileAssetRepository;
import com.storagehub.domain.repo.PaymentComplaintRepository;
import com.storagehub.domain.repo.ReservationGoodsItemRepository;
import com.storagehub.domain.repo.UserRepository;
import com.storagehub.security.ActorPrincipal;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class FileStorageServiceTests {

    @Mock FileAssetRepository fileAssetRepository;
    @Mock ReservationGoodsItemRepository goodsItemRepository;
    @Mock UserRepository userRepository;
    @Mock PaymentComplaintRepository paymentComplaintRepository;
    @Mock AdminAuthorizationService authorizationService;
    @Mock FacilityScopeService facilityScopeService;
    @Mock AuditLogService auditLogService;

    @TempDir Path temporaryDirectory;

    private FileStorageService service;
    private User customer;
    private ActorPrincipal actor;
    private ReservationGoodsItem goodsItem;

    @BeforeEach
    void setUp() {
        FileProperties properties = new FileProperties();
        properties.setStoragePath(temporaryDirectory.toString());
        service = new FileStorageService(
            properties, fileAssetRepository, goodsItemRepository, userRepository,
            paymentComplaintRepository, authorizationService, facilityScopeService, auditLogService
        );
        customer = entity(new User());
        actor = new ActorPrincipal(customer.getId(), UUID.randomUUID(), Set.of(RoleCode.CUSTOMER), Set.of(), Map.of());
        Reservation reservation = entity(new Reservation());
        reservation.setCustomer(customer);
        goodsItem = entity(new ReservationGoodsItem());
        goodsItem.setReservation(reservation);
        when(userRepository.findById(customer.getId())).thenReturn(Optional.of(customer));
    }

    @Test
    void storesPngForOwnedReservationGoodsItem() {
        byte[] png = new byte[] {(byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a};
        var file = new MockMultipartFile("file", "hang-hoa.png", "image/png", png);
        when(goodsItemRepository.findById(goodsItem.getId())).thenReturn(Optional.of(goodsItem));
        when(fileAssetRepository.saveAndFlush(any())).thenAnswer(invocation -> entity(invocation.getArgument(0)));

        var stored = service.store(actor, file, "RESERVATION_GOODS_ITEM", goodsItem.getId());

        assertThat(stored.entityType()).isEqualTo("RESERVATION_GOODS_ITEM");
        assertThat(stored.entityId()).isEqualTo(goodsItem.getId());
        assertThat(stored.contentType()).isEqualTo("image/png");
    }

    @Test
    void rejectsGoodsImageOwnedByAnotherCustomer() {
        User otherCustomer = entity(new User());
        goodsItem.getReservation().setCustomer(otherCustomer);
        when(goodsItemRepository.findById(goodsItem.getId())).thenReturn(Optional.of(goodsItem));
        var file = new MockMultipartFile("file", "hang-hoa.png", "image/png",
            new byte[] {(byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a});

        assertThatThrownBy(() -> service.store(actor, file, "RESERVATION_GOODS_ITEM", goodsItem.getId()))
            .hasMessageContaining("does not belong");
        verify(fileAssetRepository, never()).saveAndFlush(any());
    }

    @Test
    void rejectsFileWhoseBytesDoNotMatchDeclaredPngMime() {
        when(goodsItemRepository.findById(goodsItem.getId())).thenReturn(Optional.of(goodsItem));
        var file = new MockMultipartFile("file", "fake.png", "image/png", "not-a-png".getBytes());

        assertThatThrownBy(() -> service.store(actor, file, "RESERVATION_GOODS_ITEM", goodsItem.getId()))
            .hasMessageContaining("does not match");
        verify(fileAssetRepository, never()).saveAndFlush(any());
    }

    private static <T> T entity(T value) {
        ReflectionTestUtils.setField(value, "id", UUID.randomUUID());
        return value;
    }
}
