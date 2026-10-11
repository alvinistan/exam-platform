package com.leenex.exam.catalog.media;

import com.leenex.exam.catalog.support.AuditLog;
import com.leenex.exam.platform.web.ApiException;
import com.leenex.exam.platform.web.ProblemCode;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.ImageOutputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Question images (FR-QB-02, ADR-012). An upload is checked by its bytes, not its name or declared type, decoded with
 * a size limit, and re-encoded: the stored file is a fresh PNG or JPEG with no metadata and nothing hidden in it.
 * Images are served only through short-lived signed URLs.
 */
@Service
public class MediaService {

    private static final Logger log = LoggerFactory.getLogger(MediaService.class);

    public static final int MAX_BYTES = 2 * 1024 * 1024;
    static final int MAX_SIDE = 4096;

    public record StoredMedia(String key, String contentType, int width, int height, String url) {
    }

    public record Media(String contentType, byte[] bytes) {
    }

    private final JdbcClient jdbc;
    private final MediaStorage storage;
    private final AuditLog audit;
    private final Clock clock;
    private final MediaProperties properties;
    private final byte[] signingKey;

    public MediaService(JdbcClient jdbc, MediaStorage storage, AuditLog audit, Clock clock, MediaProperties properties) {
        this.jdbc = jdbc;
        this.storage = storage;
        this.audit = audit;
        this.clock = clock;
        this.properties = properties;
        this.signingKey = signingKey(properties);
    }

    @Transactional
    public StoredMedia upload(UUID actorId, byte[] bytes) {
        if (bytes.length == 0 || bytes.length > MAX_BYTES) {
            throw invalid("Images must be at most 2 MB.");
        }
        String format = sniff(bytes).orElseThrow(() -> invalid("Upload a PNG, JPEG or WebP image."));
        BufferedImage image = decode(bytes, format);
        boolean keepAlpha = image.getColorModel().hasAlpha() || !"jpeg".equals(format);
        String outFormat = keepAlpha ? "png" : "jpg";
        byte[] clean = encode(image, outFormat);
        if (clean.length > MAX_BYTES) {
            throw invalid("This image is too large once cleaned. Use a smaller image or a JPEG.");
        }
        String key = HexFormat.of().formatHex(randomBytes(16)) + "." + outFormat;
        String contentType = "png".equals(outFormat) ? "image/png" : "image/jpeg";
        storage.put(key, clean);
        jdbc.sql("""
                INSERT INTO catalog.media_object (key, content_type, size_bytes, sha256, uploaded_by)
                VALUES (:key, :type, :size, :sha, :actor)
                """)
                .param("key", key).param("type", contentType).param("size", clean.length)
                .param("sha", sha256(clean)).param("actor", actorId)
                .update();
        audit.record(actorId, "MEDIA_UPLOADED", "MEDIA", key, Map.of("bytes", clean.length));
        return new StoredMedia(key, contentType, image.getWidth(), image.getHeight(), signedUrl(key));
    }

    public boolean exists(String key) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM catalog.media_object WHERE key = :key)")
                .param("key", key).query(Boolean.class).single();
    }

    /** A URL that works for {@link MediaProperties#urlLifetime}, rounded so repeated calls give the same URL. */
    public String signedUrl(String key) {
        if (key == null) {
            return null;
        }
        long lifetime = properties.urlLifetime().toSeconds();
        long now = clock.instant().getEpochSecond();
        long expires = (now / lifetime + 2) * lifetime; // between one and two lifetimes ahead, cache-friendly
        return "/api/media/" + key + "?expires=" + expires + "&sig=" + sign(key, expires);
    }

    /** The image, if the signature is valid and unexpired; empty otherwise (callers answer 404 either way). */
    public Optional<Media> open(String key, long expires, String signature) {
        if (expires < clock.instant().getEpochSecond() || signature == null
                || !MessageDigest.isEqual(sign(key, expires).getBytes(StandardCharsets.US_ASCII),
                signature.getBytes(StandardCharsets.US_ASCII))) {
            return Optional.empty();
        }
        Optional<String> type = jdbc.sql("SELECT content_type FROM catalog.media_object WHERE key = :key")
                .param("key", key).query(String.class).optional();
        return type.flatMap(t -> storage.get(key).map(bytes -> new Media(t, bytes)));
    }

    // --- bytes ------------------------------------------------------------------------------------------------------

    /** Identifies the format from the file's first bytes. */
    static Optional<String> sniff(byte[] b) {
        if (b.length >= 8 && (b[0] & 0xff) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G') {
            return Optional.of("png");
        }
        if (b.length >= 3 && (b[0] & 0xff) == 0xff && (b[1] & 0xff) == 0xd8 && (b[2] & 0xff) == 0xff) {
            return Optional.of("jpeg");
        }
        if (b.length >= 12 && b[0] == 'R' && b[1] == 'I' && b[2] == 'F' && b[3] == 'F'
                && b[8] == 'W' && b[9] == 'E' && b[10] == 'B' && b[11] == 'P') {
            return Optional.of("webp");
        }
        return Optional.empty();
    }

    /** Reads the dimensions first, so a tiny file that claims a huge picture is refused before it is decoded. */
    private static BufferedImage decode(byte[] bytes, String format) {
        try (ImageInputStream in = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            Iterator<ImageReader> readers = ImageIO.getImageReadersByFormatName(format);
            if (!readers.hasNext()) {
                throw invalid("This image format isn't supported.");
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(in, true, true);
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                if (width < 1 || height < 1 || width > MAX_SIDE || height > MAX_SIDE) {
                    throw invalid("Images can be at most " + MAX_SIDE + " × " + MAX_SIDE + " pixels.");
                }
                return reader.read(0);
            } finally {
                reader.dispose();
            }
        } catch (IOException | RuntimeException e) {
            if (e instanceof ApiException api) {
                throw api;
            }
            throw invalid("This file isn't a readable image.");
        }
    }

    private static byte[] encode(BufferedImage image, String format) {
        BufferedImage source = image;
        if ("jpg".equals(format) && image.getType() != BufferedImage.TYPE_INT_RGB) {
            source = new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_INT_RGB);
            source.getGraphics().drawImage(image, 0, 0, null);
        }
        try (ByteArrayOutputStream out = new ByteArrayOutputStream();
             ImageOutputStream ios = ImageIO.createImageOutputStream(out)) {
            ImageWriter writer = ImageIO.getImageWritersByFormatName(format).next();
            try {
                writer.setOutput(ios);
                ImageWriteParam param = writer.getDefaultWriteParam();
                if ("jpg".equals(format)) {
                    param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                    param.setCompressionQuality(0.88f);
                }
                writer.write(null, new IIOImage(source, null, null), param);
            } finally {
                writer.dispose();
            }
            ios.flush();
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("Couldn't encode image", e);
        }
    }

    // --- signing ----------------------------------------------------------------------------------------------------

    private String sign(String key, long expires) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(signingKey, "HmacSHA256"));
            byte[] sig = mac.doFinal((key + "\n" + expires).getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(sig);
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException(e);
        }
    }

    private static byte[] signingKey(MediaProperties properties) {
        String configured = properties.signingKey();
        if (configured != null && !configured.isBlank()) {
            byte[] key = Base64.getDecoder().decode(configured.strip());
            if (key.length < 32) {
                throw new IllegalStateException("catalog.media.signing-key must be at least 32 bytes (base64).");
            }
            return key;
        }
        if (properties.requireSigningKey()) {
            throw new IllegalStateException("CATALOG_MEDIA_SIGNING_KEY is required in this environment.");
        }
        log.warn("No media signing key configured; using a temporary one. Media URLs stop working after a restart.");
        return randomBytes(32);
    }

    private static byte[] randomBytes(int n) {
        byte[] bytes = new byte[n];
        new SecureRandom().nextBytes(bytes);
        return bytes;
    }

    private static byte[] sha256(byte[] bytes) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(bytes);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static ApiException invalid(String message) {
        return new ApiException(ProblemCode.VALIDATION_FAILED, message);
    }
}
