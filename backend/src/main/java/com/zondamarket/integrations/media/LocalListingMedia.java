package com.zondamarket.integrations.media;

import com.zondamarket.catalog.service.ListingMediaStorage;
import java.io.IOException;
import java.io.InputStream;
import java.util.Optional;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "zonda.media.provider", havingValue = "local")
class LocalListingMedia implements ListingMediaStorage {
    record Content(String mediaType, byte[] data) {}

    private final JdbcClient sql;

    LocalListingMedia(JdbcClient sql) {
        this.sql = sql;
    }

    @Override
    public String provider() {
        return "local";
    }

    @Override
    public StoredImage upload(UUID publicId, InputStream content, long size, Format format) throws IOException {
        byte[] data = content.readNBytes(Math.toIntExact(size) + 1);
        try {
            sql.sql("insert into zonda_market.local_listing_media (public_id, content_type, payload) values (:id, :type, :data)")
                    .param("id", publicId).param("type", format.contentType()).param("data", data).update();
        } catch (DataAccessException failure) {
            throw new StorageException("No se pudo guardar la imagen local.");
        }
        String url = "/api/v1/media/" + publicId;
        return new StoredImage(url, url);
    }

    Optional<Content> read(UUID id) {
        return sql.sql("""
                select content_type, payload from zonda_market.local_listing_media local_media
                join zonda_market.listing_image image on image.public_id = local_media.public_id
                where local_media.public_id = :id and not image.deleted
                """).param("id", id).query((row, index) ->
                new Content(row.getString("content_type"), row.getBytes("payload"))).optional();
    }

    @Override
    public void delete(UUID publicId) {
        try {
            sql.sql("delete from zonda_market.local_listing_media where public_id = :id")
                    .param("id", publicId).update();
        } catch (DataAccessException failure) {
            throw new StorageException("No se pudo eliminar la imagen local.");
        }
    }
}
