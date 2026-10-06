package com.storagehub.api.file;

import com.storagehub.common.api.ApiResponse;
import com.storagehub.common.api.CorrelationIdContext;
import com.storagehub.security.ActorContext;
import com.storagehub.service.FileStorageService;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import java.nio.charset.StandardCharsets;
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

    @org.springframework.web.bind.annotation.GetMapping("/{fileId}")
    public ResponseEntity<org.springframework.core.io.Resource> download(@org.springframework.web.bind.annotation.PathVariable UUID fileId) {
        FileStorageService.DownloadedFile file = fileStorageService.download(actorContext.required(), fileId);
        return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType(file.contentType()))
            .contentLength(file.sizeBytes())
            .header("X-Content-SHA256", file.checksumSha256())
            .header(
                HttpHeaders.CONTENT_DISPOSITION,
                ContentDisposition.attachment().filename(file.fileName(), StandardCharsets.UTF_8).build().toString()
            )
            .body(file.resource());
    }
}
