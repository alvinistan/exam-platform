package com.leenex.exam.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockMultipartFile;

/** Question images: checked by content, re-encoded, served only through signed links (FR-QB-02, ADR-012). */
class MediaTests extends CatalogTestSupport {

    /** A 3 × 2 lossless WebP with transparency. */
    private static final byte[] WEBP = Base64.getDecoder().decode("UklGRhwAAABXRUJQVlA4TA8AAAAvAkAAEAcQ/Y8CBiKi/wEA");

    MockHttpServletResponse upload(byte[] bytes, String name, String token) throws Exception {
        return mvc.perform(multipart("/api/admin/catalog/media")
                        .file(new MockMultipartFile("file", name, "application/octet-stream", bytes))
                        .header("Authorization", "Bearer " + token))
                .andReturn().getResponse();
    }

    static byte[] image(String format, int width, int height) throws Exception {
        BufferedImage img = new BufferedImage(width, height,
                "png".equals(format) ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB);
        img.getGraphics().setColor(Color.BLUE);
        img.getGraphics().fillRect(0, 0, width / 2, height / 2);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, format, out);
        return out.toByteArray();
    }

    @Test
    void uploadedImagesAreReEncodedAndServedThroughSignedLinks() throws Exception {
        MockHttpServletResponse png = expect(upload(image("png", 40, 30), "diagram.png", ADMIN), 201);
        String key = read(png, "$.key");
        String url = read(png, "$.url");
        assertThat(key).matches("[a-f0-9]{32}\\.png");
        assertThat((Integer) read(png, "$.width")).isEqualTo(40);
        assertThat(url).startsWith("/api/media/" + key + "?expires=").contains("&sig=");
        assertThat(auditCount("MEDIA_UPLOADED", key)).isEqualTo(1);

        MockHttpServletResponse served = call(get(url), null, null);
        expect(served, 200);
        assertThat(served.getContentType()).isEqualTo("image/png");
        assertThat(served.getHeader("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(served.getHeader("Cache-Control")).contains("private");
        BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(served.getContentAsByteArray()));
        assertThat(decoded.getWidth()).isEqualTo(40);

        MockHttpServletResponse jpeg = expect(upload(image("jpg", 20, 20), "photo.jpeg", ADMIN), 201);
        assertThat((String) read(jpeg, "$.key")).endsWith(".jpg");
        assertThat((String) read(jpeg, "$.contentType")).isEqualTo("image/jpeg");

        MockHttpServletResponse webp = expect(upload(WEBP, "icon.webp", ADMIN), 201);
        assertThat((String) read(webp, "$.contentType")).isEqualTo("image/png");
    }

    @Test
    void brokenOrForgedLinksAreNotFound() throws Exception {
        String url = read(expect(upload(image("png", 8, 8), "x.png", ADMIN), 201), "$.url");
        String base = url.substring(0, url.indexOf("&sig="));
        expect(call(get(base + "&sig=AAAA"), null, null), 404);
        expect(call(get(url.replaceFirst("expires=\\d+", "expires=1000")), null, null), 404);
        expect(call(get(url.substring(0, url.indexOf('?'))), null, null), 404);
        expect(call(get("/api/media/0123456789abcdef0123456789abcdef.png?expires=99999999999&sig=x"), null, null), 404);
    }

    @Test
    void onlyRealImagesWithinLimitsAreAccepted() throws Exception {
        assertThat((String) read(expect(upload("<svg onload=alert(1)>".getBytes(StandardCharsets.UTF_8), "a.png", ADMIN), 400),
                "$.detail")).isEqualTo("Upload a PNG, JPEG or WebP image.");
        byte[] fakePng = new byte[64];
        System.arraycopy(new byte[] {(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10}, 0, fakePng, 0, 8);
        assertThat((String) read(expect(upload(fakePng, "a.png", ADMIN), 400), "$.detail"))
                .isEqualTo("This file isn't a readable image.");
        assertThat((String) read(expect(upload(image("png", 5000, 1), "wide.png", ADMIN), 400), "$.detail"))
                .contains("4096");
        expect(upload(new byte[2 * 1024 * 1024 + 1], "big.png", ADMIN), 400);
        expect(upload(image("png", 4, 4), "a.png", STUDENT), 403);
    }

    @Test
    void questionsCarryImagesWithAltTextAndSignedLinks() throws Exception {
        Content content = createContent();
        String key = read(expect(upload(image("png", 10, 10), "ecg.png", ADMIN), 201), "$.key");

        Map<String, Object> noAlt = questionInput(content.topicId(), "MAIN", "Read the ECG");
        noAlt.put("stemMediaKey", key);
        assertThat((String) read(expect(adminPost("/api/admin/catalog/questions", noAlt), 400), "$.errors[0].field"))
                .isEqualTo("stemMediaAlt");

        Map<String, Object> unknown = questionInput(content.topicId(), "MAIN", "Read the ECG");
        unknown.put("stemMediaKey", "0123456789abcdef0123456789abcdef.png");
        unknown.put("stemMediaAlt", "ECG strip");
        expect(adminPost("/api/admin/catalog/questions", unknown), 400);

        Map<String, Object> ok = questionInput(content.topicId(), "MAIN", "Read the ECG");
        ok.put("stemMediaKey", key);
        ok.put("stemMediaAlt", "ECG strip showing sinus rhythm");
        Map<String, Object> pictureOption = option("", false);
        pictureOption.put("mediaKey", key);
        pictureOption.put("mediaAlt", "Second strip");
        ok.put("options", List.of(option("a", true), pictureOption, option("c", false), option("d", false)));
        MockHttpServletResponse created = expect(adminPost("/api/admin/catalog/questions", ok), 201);
        assertThat((String) read(created, "$.stemMediaUrl")).startsWith("/api/media/" + key + "?");
        assertThat((String) read(created, "$.options[1].mediaUrl")).startsWith("/api/media/" + key + "?");
        // An option that is only a picture counts as filled in.
        assertThat((Boolean) read(created, "$.validation.valid")).isTrue();
        assertThat(UUID.fromString(read(created, "$.id"))).isNotNull();
    }
}
