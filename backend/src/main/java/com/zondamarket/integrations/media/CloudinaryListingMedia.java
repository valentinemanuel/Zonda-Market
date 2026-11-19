package com.zondamarket.integrations.media;

import com.zondamarket.catalog.service.ListingMediaStorage;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

@Component
@ConditionalOnProperty(name = "zonda.media.provider", havingValue = "cloudinary")
class CloudinaryListingMedia implements ListingMediaStorage {
    private final RestClient client;
    private final String deliveryPrefix;

    @Autowired
    CloudinaryListingMedia(@Value("${zonda.media.cloud-name}") String cloudName,
                            @Value("${zonda.media.api-key}") String apiKey,
                            @Value("${zonda.media.api-secret}") String apiSecret) {
        this(cloudName, apiKey, apiSecret, URI.create("https://api.cloudinary.com"));
    }

    CloudinaryListingMedia(String cloudName, String apiKey, String apiSecret, URI origin) {
        if (!cloudName.matches("[A-Za-z0-9_-]{1,80}") || apiKey.isBlank() || apiSecret.isBlank()) {
            throw new IllegalArgumentException("Configurá CLOUDINARY_CLOUD_NAME, CLOUDINARY_API_KEY y CLOUDINARY_API_SECRET.");
        }
        var requests = new JdkClientHttpRequestFactory(HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(4)).build());
        requests.setReadTimeout(Duration.ofSeconds(35));
        client = RestClient.builder().requestFactory(requests)
                .baseUrl(origin.resolve("/v1_1/" + cloudName + "/image").toString())
                .defaultHeaders(headers -> headers.setBasicAuth(apiKey, apiSecret))
                .build();
        deliveryPrefix = "https://res.cloudinary.com/" + cloudName + "/image/upload/";
    }

    @Override
    public String provider() {
        return "cloudinary";
    }

    @Override
    public StoredImage upload(UUID publicId, InputStream content, long size, Format format) {
        var body = new LinkedMultiValueMap<String, Object>();
        body.add("public_id", publicId.toString());
        body.add("overwrite", "false");
        body.add("file", new InputStreamResource(content) {
            @Override
            public String getFilename() {
                return publicId + format.extension();
            }

            @Override
            public long contentLength() {
                return size;
            }
        });
        Map<?, ?> result = send("/upload", MediaType.MULTIPART_FORM_DATA, body);
        if (!publicId.toString().equals(result.get("public_id"))
                || !(result.get("secure_url") instanceof String url) || !url.startsWith(deliveryPrefix)) {
            throw new StorageException("Cloudinary devolvió una imagen inesperada.");
        }
        String path = url.substring(deliveryPrefix.length());
        if (!path.matches("v[0-9]+/" + publicId + "\\.(jpg|jpeg|png|webp)")) {
            throw new StorageException("Cloudinary devolvió una URL inesperada.");
        }
        return new StoredImage(deliveryPrefix + "c_limit,w_1200,h_1200,q_auto,f_auto/" + path,
                deliveryPrefix + "c_fill,w_240,h_240,q_auto,f_auto/" + path);
    }

    @Override
    public void delete(UUID publicId) {
        var body = new LinkedMultiValueMap<String, String>();
        body.add("public_id", publicId.toString());
        body.add("invalidate", "true");
        Object result = send("/destroy", MediaType.APPLICATION_FORM_URLENCODED, body).get("result");
        if (!"ok".equals(result) && !"not found".equals(result)) {
            throw new StorageException("Cloudinary no confirmó el borrado.");
        }
    }

    private Map<?, ?> send(String path, MediaType type, Object body) {
        try {
            var result = client.post().uri(path).contentType(type).body(body).retrieve().body(Map.class);
            if (result != null) {
                return result;
            }
        } catch (RestClientException failure) {
            // No se propagan cabeceras ni respuestas del proveedor al cliente.
        }
        throw new StorageException("Cloudinary no confirmó la operación.");
    }
}
