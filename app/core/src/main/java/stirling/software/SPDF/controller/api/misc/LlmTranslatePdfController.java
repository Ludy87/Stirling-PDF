package stirling.software.SPDF.controller.api.misc;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import stirling.software.SPDF.model.api.misc.PdfPageText;
import stirling.software.SPDF.model.api.misc.TranslatePdfRequest;
import stirling.software.SPDF.service.LlmTranslationService;
import stirling.software.SPDF.service.misc.TranslatedPdfRenderer;
import stirling.software.common.model.ApplicationProperties;
import stirling.software.common.service.CustomPDFDocumentFactory;
import stirling.software.common.util.ExceptionUtils;
import stirling.software.common.util.GeneralUtils;
import stirling.software.common.util.WebResponseUtils;

@RestController
@RequestMapping("/api/v1/llm")
@Tag(name = "LLM", description = "LLM APIs")
@RequiredArgsConstructor
@Slf4j
public class LlmTranslatePdfController {

    private final CustomPDFDocumentFactory pdfDocumentFactory;
    private final LlmTranslationService translationService;
    private final TranslatedPdfRenderer translatedPdfRenderer;

    private final ApplicationProperties applicationProperties;

    @PostMapping(value = "/translate-pdf", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(
            summary = "Translate a PDF document using the configured LLM service",
            description =
                    "Extracts text from each page of the provided PDF, sends it to the configured"
                            + " large language model for translation, and returns a new PDF containing"
                            + " the translated text.")
    public ResponseEntity<byte[]> translatePdf(@ModelAttribute TranslatePdfRequest request)
            throws Exception {

        var llmConfig = applicationProperties.getSystem().getLlm();

        if (!llmConfig.isEnabled() || !llmConfig.getSupportedFunctions().contains("translation")) {
            throw ExceptionUtils.createIllegalArgumentException(
                    "translatePdf.error.notEnabled",
                    "LLM translation is disabled. Enable system.llm in settings. Add"
                            + " 'supportedFunctions' with 'translation' to enable.");
        }

        MultipartFile fileInput = request.getFileInput();

        if (fileInput == null || fileInput.isEmpty()) {
            throw ExceptionUtils.createPdfFileRequiredException();
        }
        if (!StringUtils.hasText(request.getTargetLanguage())) {
            throw ExceptionUtils.createIllegalArgumentException(
                    "translatePdf.error.targetLanguageRequired", "Target language is required.");
        }
        if (!translationService.isEnabled()) {
            throw ExceptionUtils.createIllegalArgumentException(
                    "translatePdf.error.notEnabled",
                    "LLM translation is disabled. Enable system.llm in settings.");
        }

        log.info(
                "Translating '{}' to '{}' using LLM",
                fileInput.getOriginalFilename(),
                request.getTargetLanguage());

        try (PDDocument inputDocument = pdfDocumentFactory.load(fileInput)) {
            List<PdfPageText> pages = extractPageTexts(inputDocument);
            Map<Integer, String> translations = translationService.translatePages(pages, request);
            PDDocument outputDocument =
                    pdfDocumentFactory.createNewDocumentBasedOnOldDocument(inputDocument);
            boolean includeOriginal = request.isIncludeOriginalText();
            var config = translationService.getConfig();
            if (config != null && !config.isAllowOriginalText()) {
                includeOriginal = false;
            }

            translatedPdfRenderer.render(
                    outputDocument, inputDocument, pages, translations, request, includeOriginal);

            String outputName =
                    GeneralUtils.generateFilename(
                            fileInput.getOriginalFilename(), "_translated.pdf");
            return WebResponseUtils.pdfDocToWebResponse(outputDocument, outputName);
        }
    }

    private List<PdfPageText> extractPageTexts(PDDocument document) throws IOException {
        List<PdfPageText> pages = new ArrayList<>();
        PDFTextStripper stripper = new PDFTextStripper();
        stripper.setSortByPosition(true);
        for (int pageIndex = 1; pageIndex <= document.getNumberOfPages(); pageIndex++) {
            stripper.setStartPage(pageIndex);
            stripper.setEndPage(pageIndex);
            String text = stripper.getText(document);
            pages.add(new PdfPageText(pageIndex, text != null ? text.trim() : ""));
        }
        return pages;
    }
}
