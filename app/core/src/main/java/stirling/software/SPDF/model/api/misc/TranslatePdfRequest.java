package stirling.software.SPDF.model.api.misc;

import io.swagger.v3.oas.annotations.media.Schema;

import jakarta.validation.constraints.NotBlank;

import lombok.Data;
import lombok.EqualsAndHashCode;

import stirling.software.common.model.api.PDFFile;

@Data
@EqualsAndHashCode(callSuper = true)
public class TranslatePdfRequest extends PDFFile {

    @Schema(
            description = "Target language for the translation",
            example = "fr",
            requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "Target language is required")
    private String targetLanguage;

    @Schema(
            description = "Source language of the PDF (leave blank for auto-detect)",
            example = "en")
    private String sourceLanguage;

    @Schema(description = "Optional override for the configured provider", example = "ollama")
    private String provider;

    @Schema(description = "Optional override for the configured model", example = "gpt-4o-mini")
    private String model;

    @Schema(description = "Optional additional prompt instructions for the translator")
    private String customPrompt;

    @Schema(
            description = "Include original text before the translated content in the output PDF",
            example = "false")
    private boolean includeOriginalText;

    @Schema(description = "Font size used for the translated PDF", example = "12")
    private float fontSize = 12.0f;
}
