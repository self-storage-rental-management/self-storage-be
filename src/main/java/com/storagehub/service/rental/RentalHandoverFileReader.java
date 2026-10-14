package com.storagehub.service.rental;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.storagehub.api.checkin.CompleteCheckInRequest;
import com.storagehub.common.api.ApiExceptions;
import com.storagehub.domain.model.*;
import com.storagehub.security.ActorPrincipal;
import com.storagehub.service.RentalQueryService;
import com.storagehub.service.renewal.operations.RenewalOperationEvent;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service @RequiredArgsConstructor @Transactional(readOnly=true)
public class RentalHandoverFileReader {
    private final RentalQueryService rentals;
    private final EntityManager em;
    private final ObjectMapper mapper;
    public record Document(UUID id,String name,String contentType,long sizeBytes,Instant uploadedAt,String kind) {}
    public List<Document> read(ActorPrincipal actor,UUID rentalId,boolean manager) {
        var rental=rentals.detail(actor,rentalId,manager); // Reuse ownership and facility scope checks.
        var checkIns=em.createQuery("select c from CheckIn c where (c.rental.id=:rental or (c.rental is null and c.reservation.id=:reservation)) and c.status=:status order by c.checkedInAt desc,c.id desc",CheckIn.class)
            .setParameter("rental",rentalId).setParameter("reservation",rental.reservationId()).setParameter("status",CheckInStatus.completed).getResultList();
        var result=new LinkedHashMap<UUID,Document>();
        for(var checkIn:checkIns) {
            final CompleteCheckInRequest handover;
            try { handover=mapper.readValue(checkIn.getChecklistJson(),CompleteCheckInRequest.class); }
            catch(Exception invalid){throw ApiExceptions.conflict("Completed handover document references require reconciliation");}
            var referencedFiles=new HashSet<UUID>();
            if(handover.evidenceReferences()!=null) {
                for(String reference:handover.evidenceReferences()) {
                    final UUID id;
                    try{id=UUID.fromString(reference);}catch(Exception invalid){throw ApiExceptions.conflict("Invalid handover file reference");}
                    referencedFiles.add(id);
                }
            }
            var assets=em.createQuery("select f from FileAsset f where f.entityType=:type and f.entityId=:checkIn and f.status=:status order by f.createdAt asc",FileAsset.class)
                .setParameter("type","CHECK_IN").setParameter("checkIn",checkIn.getId()).setParameter("status",FileAssetStatus.ACTIVE).getResultList();
            for(var file:assets) {
                boolean signedContract=isSignedContract(file);
                if(!signedContract&&!referencedFiles.contains(file.getId()))continue;
                String kind=signedContract?"SIGNED_CONTRACT":"HANDOVER_EVIDENCE";
                result.putIfAbsent(file.getId(),new Document(file.getId(),file.getOriginalName(),file.getContentType(),file.getSizeBytes(),file.getCreatedAt(),kind));
            }
        }
        var completions=em.createQuery("select e from RenewalOperationEvent e where e.renewal.rental.id=:rental and e.renewal.status=:status and e.kind='COMPLETION' order by e.occurredAt desc,e.id desc",RenewalOperationEvent.class)
            .setParameter("rental",rentalId).setParameter("status",RenewalStatus.completed).getResultList();
        for(var completion:completions) {
            final UUID fileId;
            try {fileId=UUID.fromString(mapper.readTree(completion.getPayloadJson()).path("signedDocumentFileId").asText());}
            catch(Exception invalid){throw ApiExceptions.conflict("Signed renewal document references require reconciliation");}
            var file=em.find(FileAsset.class,fileId);
            if(file==null||file.getStatus()!=FileAssetStatus.ACTIVE||!"DUONG_RENEWAL_SIGNED_CONTRACT".equals(file.getEntityType())
                ||!completion.getRenewal().getId().equals(file.getEntityId()))continue;
            result.putIfAbsent(fileId,new Document(fileId,file.getOriginalName(),file.getContentType(),file.getSizeBytes(),file.getCreatedAt(),"SIGNED_CONTRACT"));
        }
        return List.copyOf(result.values());
    }
    private static boolean isSignedContract(FileAsset file) {
        String name=Objects.toString(file.getOriginalName(),"").toLowerCase(Locale.ROOT);
        return name.startsWith("hop-dong-da-ky-");
    }
}
