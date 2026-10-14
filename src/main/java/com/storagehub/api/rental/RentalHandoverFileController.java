package com.storagehub.api.rental;

import com.storagehub.common.api.*;
import com.storagehub.security.ActorContext;
import com.storagehub.service.rental.RentalHandoverFileReader;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController @RequiredArgsConstructor
public class RentalHandoverFileController {
    private final ActorContext actors;
    private final RentalHandoverFileReader files;
    @GetMapping("/api/{role:customer|manager}/rentals/{id}/handover-files")
    public ApiResponse<List<RentalHandoverFileReader.Document>> read(@PathVariable String role,@PathVariable UUID id) {
        return new ApiResponse<>(files.read(actors.required(),id,"manager".equals(role)),CorrelationIdContext.current());
    }
}
