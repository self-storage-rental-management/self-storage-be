package com.storagehub.service.rental;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.storagehub.api.checkin.CompleteCheckInRequest;
import com.storagehub.common.api.ApiExceptions;
import com.storagehub.domain.model.*;
import com.storagehub.security.ActorPrincipal;
import com.storagehub.service.RentalQueryService;
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
        rentals.detail(actor,rentalId,manager); // Reuse ownership and facility scope checks.
        var checkIns=em.createQuery("select c from CheckIn c where c.rental.id=:rental and c.status=:status order by c.checkedInAt desc,c.id desc",CheckIn.class)
            .setParameter("rental",rentalId).setParameter("status",CheckInStatus.completed).getResultList();
        var result=new LinkedHashMap<UUID,Document>();
        for(var checkIn:checkIns) {
            final CompleteCheckInRequest handover;
            try { handover=mapper.readValue(checkIn.getChecklistJson(),CompleteCheckInRequest.class); }
            catch(Exception invalid){throw ApiExceptions.conflict("Completed handover document references require reconciliation");}
            if(handover.evidenceReferences()==null)continue;
            for(String reference:handover.evidenceReferences()) {
                final UUID id;
                try{id=UUID.fromString(reference);}catch(Exception invalid){throw ApiExceptions.conflict("Invalid handover file reference");}
                var file=em.find(FileAsset.class,id);
                if(file==null||file.getStatus()!=FileAssetStatus.ACTIVE||!"CHECK_IN".equals(file.getEntityType())
                    ||!checkIn.getId().equals(file.getEntityId()))continue;
                String kind=file.getOriginalName().startsWith("hop-dong-da-ky-")?"SIGNED_CONTRACT":"HANDOVER_EVIDENCE";
                result.putIfAbsent(id,new Document(id,file.getOriginalName(),file.getContentType(),file.getSizeBytes(),file.getCreatedAt(),kind));
            }
        }
        return List.copyOf(result.values());
    }
}
