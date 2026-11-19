package com.zondamarket.integrations.media;

import com.zondamarket.shared.errors.ApiProblemException;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
@ConditionalOnProperty(name = "zonda.media.provider", havingValue = "local")
class LocalListingMediaController {
    private final LocalListingMedia media;

    LocalListingMediaController(LocalListingMedia media) {
        this.media = media;
    }

    @GetMapping("/api/v1/media/{id}")
    ResponseEntity<byte[]> get(@PathVariable UUID id) {
        var content = media.read(id).orElseThrow(() -> new ApiProblemException(HttpStatus.NOT_FOUND,
                "IMAGE_NOT_FOUND", "No encontramos esa imagen."));
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(content.mediaType()))
                .cacheControl(CacheControl.noStore()).header("X-Content-Type-Options", "nosniff").body(content.data());
    }
}
