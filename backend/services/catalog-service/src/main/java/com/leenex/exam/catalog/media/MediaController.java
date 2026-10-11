package com.leenex.exam.catalog.media;

import com.leenex.exam.platform.security.CurrentUser;
import com.leenex.exam.platform.web.ApiException;
import com.leenex.exam.platform.web.ProblemCode;
import java.io.IOException;
import java.time.Duration;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
public class MediaController {

    private final MediaService media;

    public MediaController(MediaService media) {
        this.media = media;
    }

    @PostMapping(path = "/api/admin/catalog/media", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<MediaService.StoredMedia> upload(@RequestParam("file") MultipartFile file) throws IOException {
        if (file.getSize() > MediaService.MAX_BYTES) {
            throw new ApiException(ProblemCode.VALIDATION_FAILED, "Images must be at most 2 MB.");
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(media.upload(CurrentUser.id(), file.getBytes()));
    }

    /** Signed media URL (no sign-in: the signature is the permission). Any failure is a plain 404. */
    @GetMapping("/api/media/{key:[a-f0-9]{32}\\.(?:png|jpg)}")
    public ResponseEntity<byte[]> get(@PathVariable String key, @RequestParam(defaultValue = "0") long expires,
                                      @RequestParam(required = false) String sig) {
        return media.open(key, expires, sig)
                .map(m -> ResponseEntity.ok()
                        .contentType(MediaType.parseMediaType(m.contentType()))
                        .cacheControl(CacheControl.maxAge(Duration.ofMinutes(10)).cachePrivate())
                        .header("X-Content-Type-Options", "nosniff")
                        .header("Content-Disposition", "inline")
                        .header("Content-Security-Policy", "default-src 'none'; sandbox")
                        .body(m.bytes()))
                .orElseThrow(() -> new ApiException(ProblemCode.NOT_FOUND, "Image not found or link expired."));
    }
}
