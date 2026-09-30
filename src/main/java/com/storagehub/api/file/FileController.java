package com.storagehub.api.file;

import com.storagehub.common.api.ApiResponse;
import com.storagehub.common.api.CorrelationIdContext;
import com.storagehub.security.ActorContext;
import com.storagehub.service.FileStorageService;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/files")
@RequiredArgsConstructor
public class FileController {

    private final ActorContext actorContext;
    private final FileStorageService fileStorageService;

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<FileAssetResponse> upload(
        @RequestParam("file") MultipartFile file,
        @RequestParam(required = false) String entityType,
        @RequestParam(required = false) UUID entityId
    ) {
        return new ApiResponse<>(
            fileStorageService.store(actorContext.required(), file, entityType, entityId),
            CorrelationIdContext.current()
        );
    }
}
