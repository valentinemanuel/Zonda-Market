package com.zondamarket.catalog.service;

import java.io.IOException;
import java.io.InputStream;
import java.util.UUID;

public interface ListingMediaStorage {
    enum Format {
        JPEG("image/jpeg", ".jpg"), PNG("image/png", ".png"), WEBP("image/webp", ".webp");

        private final String contentType;
        private final String extension;

        Format(String contentType, String extension) {
            this.contentType = contentType;
            this.extension = extension;
        }

        public String contentType() { return contentType; }
        public String extension() { return extension; }
    }

    record StoredImage(String url, String thumbnailUrl) {}

    String provider();

    StoredImage upload(UUID publicId, InputStream content, long size, Format format) throws IOException;

    void delete(UUID publicId);

    final class StorageException extends RuntimeException {
        public StorageException(String message) {
            super(message);
        }
    }
}
