package com.zondamarket.catalog.service;

import com.zondamarket.catalog.domain.Listing.Status;
import com.zondamarket.catalog.domain.ListingImage;
import com.zondamarket.catalog.dto.ListingMediaDtos.ImagePatch;
import com.zondamarket.catalog.dto.ListingMediaDtos.ImageResponse;
import com.zondamarket.catalog.repository.ListingImageRepository;
import com.zondamarket.catalog.repository.ListingMediaStore;
import com.zondamarket.catalog.service.ListingMediaStorage.Format;
import com.zondamarket.catalog.service.ListingMediaStorage.StorageException;
import com.zondamarket.shared.errors.ApiProblemException;
import java.io.IOException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

@Service
public class ListingImageService {
    private static final int MAX_IMAGES = 6;
    private final ListingService listings;
    private final ListingImageRepository images;
    private final ListingMediaStore cleanup;
    private final Optional<ListingMediaStorage> storage;
    private final ImageFormatValidator validator;
    private final Clock clock;
    private final TransactionTemplate transaction;

    ListingImageService(ListingService listings, ListingImageRepository images, ListingMediaStore cleanup,
                        Optional<ListingMediaStorage> storage, ImageFormatValidator validator, Clock clock,
                        PlatformTransactionManager transactions) {
        this.listings = listings;
        this.images = images;
        this.cleanup = cleanup;
        this.storage = storage;
        this.validator = validator;
        this.clock = clock;
        transaction = new TransactionTemplate(transactions);
    }

    public ImageResponse upload(UUID account, UUID listingId, MultipartFile file, String alt) {
        transaction.executeWithoutResult(ignored -> {
            listings.lockListing(account, listingId);
            requireAvailableSlot(images.countByListingIdAndDeletedFalse(listingId));
        });
        String description = validAlt(alt);
        if (file.getSize() > ImageFormatValidator.MAX_BYTES) {
            throw new ApiProblemException(HttpStatus.CONTENT_TOO_LARGE, "IMAGE_TOO_LARGE", "La imagen admite hasta 5 MiB.");
        }
        var format = detect(file);
        var provider = storage.orElseThrow(ListingImageService::unavailable);
        UUID publicId = UUID.randomUUID();
        var started = clock.instant();
        // Si el servidor se interrumpe después de la carga, queda registrada la limpieza.
        cleanup.enqueue(publicId, provider.provider(), started, started.plusSeconds(600));
        ListingMediaStorage.StoredImage uploaded;
        try (var content = file.getInputStream()) {
            uploaded = provider.upload(publicId, content, file.getSize(), format);
        } catch (IOException | StorageException failure) {
            throw unavailable();
        }

        try {
            return transaction.execute(ignored -> {
                listings.lockListing(account, listingId);
                long position = images.countByListingIdAndDeletedFalse(listingId);
                requireAvailableSlot(position);
                if (!cleanup.cancelUploadCleanup(publicId, clock.instant())) {
                    throw unavailable();
                }
                return ImageResponse.from(images.save(new ListingImage(listingId, provider.provider(), publicId,
                        uploaded.url(), uploaded.thumbnailUrl(), description, (int) position)));
            });
        } catch (RuntimeException failure) {
            try {
                cleanup.expedite(publicId, clock.instant());
            } catch (RuntimeException cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
            throw failure;
        }
    }

    @Transactional
    public List<ImageResponse> edit(UUID account, UUID listingId, UUID imageId, ImagePatch request) {
        listings.lockListing(account, listingId);
        var gallery = new ArrayList<>(images.findAllByListingIdAndDeletedFalseOrderByPositionAscIdAsc(listingId));
        var image = findImage(gallery, imageId);
        if (request.alt() != null) {
            image.changeAlt(validAlt(request.alt()));
        }
        if (request.position() != null) {
            int position = request.position().intValueExact();
            if (position >= gallery.size()) {
                throw new ApiProblemException(HttpStatus.BAD_REQUEST, "IMAGE_POSITION_INVALID",
                        "La posición debe corresponder a una imagen de la galería.");
            }
            gallery.remove(image);
            gallery.add(position, image);
            reindex(gallery);
        }
        return gallery.stream().map(ImageResponse::from).toList();
    }

    @Transactional
    public void delete(UUID account, UUID listingId, UUID imageId) {
        var listing = listings.lockListing(account, listingId);
        var gallery = new ArrayList<>(images.findAllByListingIdAndDeletedFalseOrderByPositionAscIdAsc(listingId));
        var image = findImage(gallery, imageId);
        if (listing.getStatus() == Status.ACTIVA && gallery.size() == 1) {
            throw new ApiProblemException(HttpStatus.CONFLICT, "IMAGE_REQUIRED",
                    "Agregá otra imagen o archivá la publicación antes de retirar esta foto.");
        }
        image.archive();
        gallery.remove(image);
        reindex(gallery);
        if (!"external".equals(image.getProvider())) {
            var now = clock.instant();
            cleanup.enqueue(image.getPublicId(), image.getProvider(), now, now);
        }
    }

    private Format detect(MultipartFile file) {
        try (var content = file.getInputStream()) {
            return validator.detect(content);
        } catch (IOException failure) {
            throw ImageFormatValidator.invalidImage();
        }
    }

    private static String validAlt(String alt) {
        String value = alt == null ? "" : alt.strip();
        if (value.length() > 160 || value.indexOf(0) >= 0) {
            throw new ApiProblemException(HttpStatus.BAD_REQUEST, "IMAGE_ALT_INVALID",
                    "El texto alternativo admite hasta 160 caracteres.");
        }
        return value;
    }

    private static void requireAvailableSlot(long count) {
        if (count >= MAX_IMAGES) {
            throw new ApiProblemException(HttpStatus.CONFLICT, "IMAGE_LIMIT_REACHED", "La publicación admite hasta seis imágenes.");
        }
    }

    private static ListingImage findImage(List<ListingImage> gallery, UUID id) {
        return gallery.stream().filter(image -> image.getId().equals(id)).findFirst()
                .orElseThrow(() -> new ApiProblemException(HttpStatus.NOT_FOUND, "IMAGE_NOT_FOUND",
                        "No encontramos esa imagen en la publicación."));
    }

    private static void reindex(List<ListingImage> gallery) {
        for (int index = 0; index < gallery.size(); index++) {
            gallery.get(index).move(index);
        }
    }

    private static ApiProblemException unavailable() {
        return new ApiProblemException(HttpStatus.SERVICE_UNAVAILABLE, "MEDIA_UNAVAILABLE",
                "El proveedor de imágenes no está disponible. Volvé a intentar.");
    }
}
