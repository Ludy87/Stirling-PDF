package stirling.software.SPDF.service.misc;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.apache.pdfbox.io.IOUtils;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.springframework.context.MessageSource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import stirling.software.SPDF.model.api.misc.PdfPageText;
import stirling.software.SPDF.model.api.misc.TranslatePdfRequest;

@Service
@RequiredArgsConstructor
@Slf4j
public class TranslatedPdfRenderer {

    private static final float DEFAULT_MARGIN = 48f;
    private static final float MIN_FONT_SIZE = 6f;
    private static final float MAX_FONT_SIZE = 36f;

    private final MessageSource messageSource;

    public void render(
            PDDocument outputDocument,
            PDDocument sourceDocument,
            List<PdfPageText> pages,
            java.util.Map<Integer, String> translations,
            TranslatePdfRequest request,
            boolean includeOriginalText)
            throws IOException {
        if (outputDocument == null || sourceDocument == null) {
            throw new IllegalArgumentException("PDDocument references must not be null");
        }

        float fontSize = sanitizeFontSize(request.getFontSize());
        Locale locale = determineLocale(request.getTargetLanguage());
        PDFont font = loadFontForLanguage(outputDocument, request.getTargetLanguage());

        for (PdfPageText pageText : pages) {
            String translation = translations.getOrDefault(pageText.getPageNumber(), "");
            PDRectangle baseBox =
                    getPageSize(sourceDocument, pageText.getPageNumber() - 1, PDRectangle.LETTER);
            List<String> lines =
                    buildContentLines(
                            pageText,
                            translation,
                            includeOriginalText,
                            font,
                            fontSize,
                            baseBox.getWidth(),
                            locale);
            writePageContent(
                    outputDocument, baseBox, font, fontSize, pageText.getPageNumber(), lines);
        }
    }

    private float sanitizeFontSize(float requestedSize) {
        float size = requestedSize > 0 ? requestedSize : 12f;
        if (size < MIN_FONT_SIZE) {
            return MIN_FONT_SIZE;
        }
        if (size > MAX_FONT_SIZE) {
            return MAX_FONT_SIZE;
        }
        return size;
    }

    private Locale determineLocale(String targetLanguage) {
        if (!StringUtils.hasText(targetLanguage)) {
            return Locale.ENGLISH;
        }
        try {
            return Locale.forLanguageTag(targetLanguage);
        } catch (Exception ex) {
            log.debug("Failed to parse locale '{}': {}", targetLanguage, ex.getMessage());
            return Locale.ENGLISH;
        }
    }

    private PDFont loadFontForLanguage(PDDocument document, String targetLanguage)
            throws IOException {
        String normalized = targetLanguage != null ? targetLanguage.toLowerCase(Locale.ROOT) : "";
        String resourcePath = "static/fonts/NotoSans-Regular.ttf";
        if (normalized.startsWith("zh")) {
            resourcePath = "static/fonts/NotoSansSC-Regular.ttf";
        } else if (normalized.startsWith("ja")) {
            resourcePath = "static/fonts/NotoSansJP-Regular.ttf";
        } else if (normalized.startsWith("ko")) {
            resourcePath = "static/fonts/malgun.ttf";
        } else if (normalized.startsWith("ar")) {
            resourcePath = "static/fonts/NotoSansArabic-Regular.ttf";
        } else if (normalized.startsWith("th")) {
            resourcePath = "static/fonts/NotoSansThai-Regular.ttf";
        }

        ClassPathResource resource = new ClassPathResource(resourcePath);
        String extension = resourcePath.substring(resourcePath.lastIndexOf('.'));
        File tempFile = File.createTempFile("stirling-font", extension);
        try (InputStream in = resource.getInputStream();
                FileOutputStream out = new FileOutputStream(tempFile)) {
            IOUtils.copy(in, out);
            return PDType0Font.load(document, tempFile);
        } finally {
            if (!tempFile.delete()) {
                tempFile.deleteOnExit();
            }
        }
    }

    private PDRectangle getPageSize(PDDocument source, int index, PDRectangle fallback) {
        if (index >= 0 && index < source.getNumberOfPages()) {
            PDRectangle box = source.getPage(index).getMediaBox();
            if (box != null) {
                return box;
            }
        }
        return fallback;
    }

    private List<String> buildContentLines(
            PdfPageText pageText,
            String translation,
            boolean includeOriginalText,
            PDFont font,
            float fontSize,
            float pageWidth,
            Locale locale)
            throws IOException {
        float usableWidth = pageWidth - (DEFAULT_MARGIN * 2);
        String headerOriginal =
                messageSource.getMessage(
                        "translatePdf.output.original", null, "Original text", Locale.ENGLISH);
        String headerTranslation =
                messageSource.getMessage(
                        "translatePdf.output.translation", null, "Translated text", Locale.ENGLISH);
        String emptyMessage =
                messageSource.getMessage(
                        "translatePdf.output.empty",
                        null,
                        "No translatable text detected on this page.",
                        Locale.ENGLISH);

        List<String> lines = new ArrayList<>();
        if (includeOriginalText && pageText.hasContent()) {
            lines.add(headerOriginal);
            lines.addAll(wrapParagraphs(pageText.getText(), font, fontSize, usableWidth, locale));
            lines.add("");
        }

        if (StringUtils.hasText(translation)) {
            lines.add(headerTranslation);
            lines.addAll(wrapParagraphs(translation, font, fontSize, usableWidth, locale));
        } else {
            lines.add(headerTranslation);
            lines.add(emptyMessage);
        }

        if (lines.isEmpty()) {
            lines.add(emptyMessage);
        }

        return lines;
    }

    private List<String> wrapParagraphs(
            String text, PDFont font, float fontSize, float maxWidth, Locale locale)
            throws IOException {
        List<String> wrapped = new ArrayList<>();
        if (!StringUtils.hasText(text)) {
            return wrapped;
        }
        String normalised = text.replace("\r\n", "\n");
        String[] rawLines = normalised.split("\n", -1);
        for (String raw : rawLines) {
            if (!StringUtils.hasText(raw)) {
                wrapped.add("");
                continue;
            }
            wrapped.addAll(wrapSingleParagraph(raw.trim(), font, fontSize, maxWidth));
        }
        return wrapped;
    }

    private List<String> wrapSingleParagraph(
            String paragraph, PDFont font, float fontSize, float maxWidth) throws IOException {
        List<String> lines = new ArrayList<>();
        if (!StringUtils.hasText(paragraph)) {
            return lines;
        }

        if (usesCjkScript(paragraph)) {
            StringBuilder current = new StringBuilder();
            for (int offset = 0; offset < paragraph.length(); ) {
                int codePoint = paragraph.codePointAt(offset);
                String character = new String(Character.toChars(codePoint));
                String candidate = current + character;
                if (getStringWidth(font, candidate, fontSize) <= maxWidth) {
                    current.append(character);
                } else {
                    if (current.length() > 0) {
                        lines.add(current.toString());
                    }
                    current = new StringBuilder(character);
                }
                offset += Character.charCount(codePoint);
            }
            if (current.length() > 0) {
                lines.add(current.toString());
            }
            return lines;
        }

        StringBuilder currentLine = new StringBuilder();
        for (String word : paragraph.split("\\s+")) {
            if (!StringUtils.hasText(word)) {
                continue;
            }
            String candidate =
                    currentLine.length() == 0 ? word : currentLine.toString() + " " + word;
            if (getStringWidth(font, candidate, fontSize) <= maxWidth) {
                currentLine.setLength(0);
                currentLine.append(candidate);
            } else {
                if (currentLine.length() > 0) {
                    lines.add(currentLine.toString());
                }
                if (getStringWidth(font, word, fontSize) <= maxWidth) {
                    currentLine.setLength(0);
                    currentLine.append(word);
                } else {
                    lines.addAll(forceBreakLongWord(word, font, fontSize, maxWidth));
                    currentLine.setLength(0);
                }
            }
        }
        if (currentLine.length() > 0) {
            lines.add(currentLine.toString());
        }
        return lines;
    }

    private List<String> forceBreakLongWord(
            String word, PDFont font, float fontSize, float maxWidth) throws IOException {
        List<String> lines = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (int offset = 0; offset < word.length(); ) {
            int codePoint = word.codePointAt(offset);
            String character = new String(Character.toChars(codePoint));
            String candidate = current + character;
            if (getStringWidth(font, candidate, fontSize) <= maxWidth) {
                current.append(character);
            } else {
                if (current.length() > 0) {
                    lines.add(current.toString());
                }
                current = new StringBuilder(character);
            }
            offset += Character.charCount(codePoint);
        }
        if (current.length() > 0) {
            lines.add(current.toString());
        }
        return lines;
    }

    private boolean usesCjkScript(String text) {
        if (!StringUtils.hasText(text)) {
            return false;
        }
        String normalized = Normalizer.normalize(text, Normalizer.Form.NFC);
        for (int offset = 0; offset < normalized.length(); ) {
            int codePoint = normalized.codePointAt(offset);
            Character.UnicodeScript script = Character.UnicodeScript.of(codePoint);
            if (script == Character.UnicodeScript.HAN
                    || script == Character.UnicodeScript.HIRAGANA
                    || script == Character.UnicodeScript.KATAKANA
                    || script == Character.UnicodeScript.HANGUL
                    || script == Character.UnicodeScript.THAI) {
                return true;
            }
            offset += Character.charCount(codePoint);
        }
        return false;
    }

    private float getStringWidth(PDFont font, String text, float fontSize) throws IOException {
        if (!StringUtils.hasText(text)) {
            return 0f;
        }
        return font.getStringWidth(text) / 1000f * fontSize;
    }

    private void writePageContent(
            PDDocument document,
            PDRectangle pageSize,
            PDFont font,
            float fontSize,
            int pageNumber,
            List<String> lines)
            throws IOException {
        String header =
                messageSource.getMessage(
                        "translatePdf.output.header",
                        new Object[] {pageNumber},
                        "Page " + pageNumber,
                        Locale.ENGLISH);
        String continuation =
                messageSource.getMessage(
                        "translatePdf.output.header.continued",
                        new Object[] {pageNumber},
                        "Page " + pageNumber + " (continued)",
                        Locale.ENGLISH);

        float leading = fontSize * 1.4f;
        PageContext context =
                openPage(document, pageSize, font, fontSize, header, leading, DEFAULT_MARGIN);
        int segment = 0;
        try {
            for (String line : lines) {
                if (context.needsNewPage()) {
                    context.close();
                    segment++;
                    context =
                            openPage(
                                    document,
                                    pageSize,
                                    font,
                                    fontSize,
                                    segment == 0 ? header : continuation,
                                    leading,
                                    DEFAULT_MARGIN);
                }
                context.writeLine(line);
            }
        } finally {
            context.close();
        }
    }

    private PageContext openPage(
            PDDocument document,
            PDRectangle pageSize,
            PDFont font,
            float fontSize,
            String header,
            float leading,
            float margin)
            throws IOException {
        PDPage page = new PDPage(pageSize);
        document.addPage(page);
        PDPageContentStream contentStream = new PDPageContentStream(document, page);
        contentStream.setFont(font, fontSize);
        contentStream.setLeading(leading);
        contentStream.beginText();
        contentStream.newLineAtOffset(margin, pageSize.getHeight() - margin);
        if (StringUtils.hasText(header)) {
            contentStream.showText(header);
            contentStream.newLine();
        }
        return new PageContext(contentStream, pageSize.getHeight(), leading, margin);
    }

    private static class PageContext implements AutoCloseable {
        private final PDPageContentStream contentStream;
        private final float pageHeight;
        private final float leading;
        private final float margin;
        private float cursorY;
        private boolean closed;

        PageContext(
                PDPageContentStream contentStream, float pageHeight, float leading, float margin) {
            this.contentStream = contentStream;
            this.pageHeight = pageHeight;
            this.leading = leading;
            this.margin = margin;
            this.cursorY = pageHeight - margin - leading;
        }

        boolean needsNewPage() {
            return cursorY <= margin;
        }

        void writeLine(String line) throws IOException {
            if (line == null || line.isEmpty()) {
                contentStream.newLine();
            } else {
                contentStream.showText(line);
                contentStream.newLine();
            }
            cursorY -= leading;
        }

        @Override
        public void close() throws IOException {
            if (!closed) {
                contentStream.endText();
                contentStream.close();
                closed = true;
            }
        }
    }
}
