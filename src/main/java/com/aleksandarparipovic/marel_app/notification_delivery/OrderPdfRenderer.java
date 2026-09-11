package com.aleksandarparipovic.marel_app.notification_delivery;

import com.aleksandarparipovic.marel_app.order_email_view.OrderEmailView;
import org.openpdf.text.pdf.BaseFont;
import org.springframework.stereotype.Component;
import org.xhtmlrenderer.pdf.ITextRenderer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The order as a PDF document — the attachment on the conversation's mails.
 *
 * <p>Rendered from the SAME {@link OrderEmailView} the mail body is rendered
 * from, in {@link OrderEmailHtmlRenderer.Mode#CLEAN} mode: the attachment is
 * the order document as it stands, so removed rows are gone and nothing is
 * marked. Whatever the body says changed, the PDF says what is now true — one
 * source, two presentations, no way for them to disagree.
 *
 * <p>The DejaVu fonts are bundled under {@code resources/fonts} and embedded
 * into every document: the PDF built-in fonts have no č/ć/š/ž/đ, and an order
 * document that mangles its own product names is worse than none. Flying Saucer
 * loads fonts from a file path, so each font is copied out of the jar to a
 * temp file once per process.
 */
@Component
public class OrderPdfRenderer {

    private static final String[] FONT_RESOURCES = {
            "/fonts/DejaVuSans.ttf",
            "/fonts/DejaVuSans-Bold.ttf",
    };

    private final Map<String, Path> extractedFonts = new ConcurrentHashMap<>();

    public byte[] render(String title, OrderEmailView view) {
        String xhtml = document(title, view);

        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            ITextRenderer renderer = new ITextRenderer();
            for (String resource : FONT_RESOURCES) {
                renderer.getFontResolver().addFont(
                        fontFile(resource).toString(), BaseFont.IDENTITY_H, true);
            }
            renderer.setDocumentFromString(xhtml);
            renderer.layout();
            renderer.createPDF(out);
            return out.toByteArray();
        } catch (IOException ex) {
            throw new UncheckedIOException("PDF naloga nije mogao da se generiše", ex);
        }
    }

    /**
     * Strict XHTML — Flying Saucer parses with an XML parser, and the shared
     * renderer emits well-formed markup precisely so this wrapper stays a
     * wrapper. The body renderer's inline styles carry over unchanged.
     */
    private static String document(String title, OrderEmailView view) {
        return "<html><head><style>"
                + "@page { size: A4; margin: 16mm; } "
                + "body { font-family: 'DejaVu Sans', sans-serif; font-size: 11px; "
                + "color: #212529; } "
                + "table { -fs-table-paginate: paginate; }"
                + "</style></head><body>"
                + "<h1 style=\"font-size:15px;margin:0 0 12px\">" + escape(title) + "</h1>"
                + OrderEmailHtmlRenderer.render(view, OrderEmailHtmlRenderer.Mode.CLEAN)
                + "</body></html>";
    }

    private Path fontFile(String resource) {
        return extractedFonts.computeIfAbsent(resource, key -> {
            try (InputStream in = OrderPdfRenderer.class.getResourceAsStream(key)) {
                if (in == null) {
                    throw new IllegalStateException("Font nije u resursima: " + key);
                }
                Path file = Files.createTempFile("marel-pdf-font", ".ttf");
                file.toFile().deleteOnExit();
                Files.copy(in, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                return file;
            } catch (IOException ex) {
                throw new UncheckedIOException("Font nije mogao da se raspakuje: " + key, ex);
            }
        });
    }

    private static String escape(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }
}
