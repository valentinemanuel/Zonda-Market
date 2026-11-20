package com.zondamarket.catalog.service;

import com.zondamarket.catalog.service.ListingMediaStorage.Format;
import com.zondamarket.shared.errors.ApiProblemException;
import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;
import java.util.concurrent.Semaphore;
import javax.imageio.ImageIO;
import javax.imageio.stream.MemoryCacheImageInputStream;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
class ImageFormatValidator {
    static final long MAX_BYTES = 5L * 1024 * 1024;
    private static final long MAX_PIXELS = 12_000_000;
    private final Semaphore decoding = new Semaphore(2);

    Format detect(InputStream bytes) {
        if (!decoding.tryAcquire()) {
            throw new ApiProblemException(HttpStatus.SERVICE_UNAVAILABLE, "IMAGE_VALIDATION_BUSY",
                    "La validación está ocupada. Volvé a intentar.");
        }
        try (var stream = new MemoryCacheImageInputStream(bytes)) {
            var readers = ImageIO.getImageReaders(stream);
            if (!readers.hasNext()) {
                throw invalidImage();
            }
            var reader = readers.next();
            try {
                Format type = switch (reader.getFormatName().toLowerCase(Locale.ROOT)) {
                    case "jpeg", "jpg" -> Format.JPEG;
                    case "png" -> Format.PNG;
                    case "webp" -> Format.WEBP;
                    default -> throw invalidImage();
                };
                reader.setInput(stream, true, true);
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                if (width < 1 || height < 1 || width > 8192 || height > 8192 || (long) width * height > MAX_PIXELS) {
                    throw new ApiProblemException(HttpStatus.BAD_REQUEST, "IMAGE_DIMENSIONS_INVALID",
                            "La imagen admite hasta 12 megapíxeles y 8192 píxeles por lado.");
                }
                reader.addIIOReadWarningListener((source, warning) -> { throw invalidImage(); });
                reader.read(0).flush();
                return type;
            } finally {
                reader.dispose();
            }
        } catch (ApiProblemException failure) {
            throw failure;
        } catch (IOException | RuntimeException failure) {
            throw invalidImage();
        } finally {
            decoding.release();
        }
    }

    static ApiProblemException invalidImage() {
        return new ApiProblemException(HttpStatus.BAD_REQUEST, "IMAGE_INVALID",
                "Elegí una imagen JPEG, PNG o WebP válida.");
    }
}
