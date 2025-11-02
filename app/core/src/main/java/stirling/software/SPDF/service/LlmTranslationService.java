package stirling.software.SPDF.service;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import stirling.software.SPDF.model.api.misc.PdfPageText;
import stirling.software.SPDF.model.api.misc.TranslatePdfRequest;
import stirling.software.common.model.ApplicationProperties;
import stirling.software.common.util.ExceptionUtils;

@Service
@RequiredArgsConstructor
@Slf4j
public class LlmTranslationService {

    private static final int MIN_TIMEOUT_SECONDS = 10;
    private static final int DEFAULT_MAX_CHARS = 4000;
    private static final double DEFAULT_TEMPERATURE = 0.2d;

    private final ApplicationProperties applicationProperties;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public boolean isEnabled() {
        ApplicationProperties.System.Llm config = getConfig();
        return config != null && config.isEnabled();
    }

    public ApplicationProperties.System.Llm getConfig() {
        ApplicationProperties.System system = applicationProperties.getSystem();
        return system != null ? system.getLlm() : null;
    }

    public Map<Integer, String> translatePages(List<PdfPageText> pages, TranslatePdfRequest request)
            throws Exception {
        ApplicationProperties.System.Llm config = getConfig();
        if (config == null
                || !config.isEnabled()
                || !config.getSupportedFunctions().contains("translation")) {
            throw ExceptionUtils.createIllegalArgumentException(
                    "translatePdf.error.notEnabled",
                    "LLM translation is disabled. Enable system.Llm in settings. Or translation"
                            + " function is not supported.");
        }

        if (pages == null || pages.isEmpty()) {
            return Map.of();
        }

        ProviderType provider = resolveProvider(request.getProvider(), config);
        String model = resolveModel(request.getModel(), config);
        if (!StringUtils.hasText(model)) {
            throw ExceptionUtils.createIllegalArgumentException(
                    "translatePdf.alert.misconfigured",
                    "No translation model configured. Update system.Llm.model.");
        }

        String baseUrl = resolveBaseUrl(config, provider);
        Duration timeout =
                Duration.ofSeconds(
                        Math.max(config.getRequestTimeoutSeconds(), MIN_TIMEOUT_SECONDS));
        double temperature =
                config.getTemperature() != null ? config.getTemperature() : DEFAULT_TEMPERATURE;

        int maxCharacters = config.getMaxChunkCharacters();
        if (maxCharacters <= 0) {
            maxCharacters = DEFAULT_MAX_CHARS;
        }

        boolean allowCustomPrompt = config.isAllowCustomPrompt();
        String prompt = buildSystemPrompt(request, config, allowCustomPrompt);

        List<PdfPageText> orderedPages = new ArrayList<>(pages);
        orderedPages.sort((a, b) -> Integer.compare(a.getPageNumber(), b.getPageNumber()));

        Map<Integer, String> translations = new LinkedHashMap<>();
        List<PdfPageText> pagesNeedingTranslation = new ArrayList<>();

        for (PdfPageText page : orderedPages) {
            if (!page.hasContent()) {
                translations.put(page.getPageNumber(), "");
            } else {
                pagesNeedingTranslation.add(page);
            }
        }

        if (pagesNeedingTranslation.isEmpty()) {
            return translations;
        }

        List<List<PdfPageText>> chunks = chunkPages(pagesNeedingTranslation, maxCharacters);
        log.debug(
                "Translating {} pages in {} chunk(s) via {} using model {}",
                pagesNeedingTranslation.size(),
                chunks.size(),
                provider,
                model);

        for (List<PdfPageText> chunk : chunks) {
            Map<Integer, String> chunkResult =
                    dispatchChunk(
                            chunk, provider, baseUrl, model, prompt, config, timeout, temperature);
            translations.putAll(chunkResult);
        }

        return translations;
    }

    List<List<PdfPageText>> chunkPages(List<PdfPageText> pages, int maxCharacters) {
        List<List<PdfPageText>> chunks = new ArrayList<>();
        List<PdfPageText> current = new ArrayList<>();
        int currentSize = 0;

        for (PdfPageText page : pages) {
            int estimatedSize = estimatePageCost(page);
            if (!current.isEmpty() && currentSize + estimatedSize > maxCharacters) {
                chunks.add(List.copyOf(current));
                current.clear();
                currentSize = 0;
            }
            current.add(page);
            currentSize += estimatedSize;
        }

        if (!current.isEmpty()) {
            chunks.add(List.copyOf(current));
        }

        return chunks;
    }

    private int estimatePageCost(PdfPageText page) {
        if (page == null || page.getText() == null) {
            return 0;
        }
        return page.getText().length() + 32;
    }

    private ProviderType resolveProvider(
            String requestProvider, ApplicationProperties.System.Llm config) {
        if (StringUtils.hasText(requestProvider) && config.isAllowProviderOverride()) {
            return ProviderType.from(requestProvider.trim());
        }
        return ProviderType.from(config.getProvider());
    }

    private String resolveModel(String requestedModel, ApplicationProperties.System.Llm config) {
        if (StringUtils.hasText(requestedModel) && config.isAllowModelOverride()) {
            return requestedModel.trim();
        }
        return StringUtils.hasText(config.getModel()) ? config.getModel().trim() : null;
    }

    private String resolveBaseUrl(ApplicationProperties.System.Llm config, ProviderType provider) {
        if (StringUtils.hasText(config.getBaseUrl())) {
            return config.getBaseUrl().trim();
        }
        return provider.getDefaultBaseUrl();
    }

    private String buildSystemPrompt(
            TranslatePdfRequest request,
            ApplicationProperties.System.Llm config,
            boolean allowCustomPrompt) {
        StringBuilder builder =
                new StringBuilder(
                        "You are a professional translation assistant. Translate the provided PDF"
                                + " text");

        if (StringUtils.hasText(request.getSourceLanguage())) {
            builder.append(" from ").append(request.getSourceLanguage().trim());
        } else {
            builder.append(" from its original language");
        }

        builder.append(" into ")
                .append(
                        StringUtils.hasText(request.getTargetLanguage())
                                ? request.getTargetLanguage().trim()
                                : "the target language")
                .append(
                        ". Preserve headings, numbering, and paragraph structure. Respond with a"
                                + " JSON object whose keys are the page numbers and whose values are"
                                + " only the translated text. Do not include explanations or additional"
                                + " commentary.");

        if (StringUtils.hasText(config.getDefaultPrompt())) {
            builder.append(' ').append(config.getDefaultPrompt().trim());
        }

        if (allowCustomPrompt && StringUtils.hasText(request.getCustomPrompt())) {
            builder.append(' ').append(request.getCustomPrompt().trim());
        }

        return builder.toString();
    }

    private Map<Integer, String> dispatchChunk(
            List<PdfPageText> chunk,
            ProviderType provider,
            String baseUrl,
            String model,
            String prompt,
            ApplicationProperties.System.Llm config,
            Duration timeout,
            double temperature)
            throws Exception {

        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("model", model);

        ArrayNode messages = payload.putArray("messages");
        ObjectNode systemNode = messages.addObject();
        systemNode.put("role", "system");
        systemNode.put("content", prompt);

        ObjectNode chunkContent = objectMapper.createObjectNode();
        for (PdfPageText page : chunk) {
            chunkContent.put(String.valueOf(page.getPageNumber()), page.getText());
        }

        ObjectNode userNode = messages.addObject();
        userNode.put("role", "user");
        userNode.put("content", objectMapper.writeValueAsString(chunkContent));

        payload.put("stream", false);
        payload.put("temperature", temperature);

        if (provider == ProviderType.OPENAI_COMPATIBLE) {
            ObjectNode responseFormat = objectMapper.createObjectNode();
            responseFormat.put("type", "json_object");
            payload.set("response_format", responseFormat);
        } else {
            ObjectNode optionsNode = payload.putObject("options");
            optionsNode.put("temperature", temperature);
        }

        URI endpoint = provider.resolveEndpoint(baseUrl);
        String requestBody = objectMapper.writeValueAsString(payload);
        Map<String, String> headers = buildHeaders(config);

        String rawResponse = performRequest(provider, endpoint, requestBody, headers, timeout);
        String assistantContent = extractAssistantContent(rawResponse, provider);
        Map<Integer, String> parsed = parseTranslationResponse(assistantContent, chunk);
        ensureAllPagesPresent(parsed.keySet(), chunk);
        return parsed;
    }

    private Map<String, String> buildHeaders(ApplicationProperties.System.Llm config) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Content-Type", "application/json");
        if (StringUtils.hasText(config.getApiKey())) {
            headers.put("Authorization", "Bearer " + config.getApiKey().trim());
        }
        return headers;
    }

    protected String performRequest(
            ProviderType provider,
            URI uri,
            String requestBody,
            Map<String, String> headers,
            Duration timeout)
            throws IOException, InterruptedException {
        HttpClient client = HttpClient.newBuilder().connectTimeout(timeout).build();
        HttpRequest.Builder builder =
                HttpRequest.newBuilder()
                        .uri(uri)
                        .timeout(timeout)
                        .POST(HttpRequest.BodyPublishers.ofString(requestBody));

        headers.forEach(builder::header);

        HttpResponse<String> response =
                client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() >= 400) {
            String body = response.body();
            String truncated =
                    body != null && body.length() > 500 ? body.substring(0, 500) + "..." : body;
            throw ExceptionUtils.createRuntimeException(
                    "translatePdf.error.httpFailure",
                    "Translation request failed with status {0} and body: {1}",
                    null,
                    response.statusCode(),
                    truncated);
        }
        return response.body();
    }

    String extractAssistantContent(String responseBody, ProviderType provider) throws IOException {
        JsonNode root = objectMapper.readTree(responseBody);

        if (root.has("choices")
                && root.get("choices").isArray()
                && root.get("choices").size() > 0) {
            JsonNode choice = root.get("choices").get(0);
            if (choice.has("message")) {
                JsonNode message = choice.get("message");
                if (message.has("content")) {
                    return message.get("content").asText();
                }
            }
            if (choice.has("text")) {
                return choice.get("text").asText();
            }
        }

        if (root.has("message") && root.get("message").has("content")) {
            return root.get("message").get("content").asText();
        }

        if (root.has("output_text")) {
            return root.get("output_text").asText();
        }

        throw new IllegalStateException("Translation response did not include assistant content");
    }

    Map<Integer, String> parseTranslationResponse(String content, List<PdfPageText> chunk)
            throws IOException {
        String cleaned = cleanupAssistantContent(content);
        JsonNode root = objectMapper.readTree(cleaned);
        if (!root.isObject()) {
            throw new IllegalStateException("Translation response was not a JSON object");
        }

        Map<Integer, String> results = new LinkedHashMap<>();
        for (PdfPageText page : chunk) {
            JsonNode node = root.get(String.valueOf(page.getPageNumber()));
            if (node == null || node.isNull()) {
                results.put(page.getPageNumber(), "");
            } else {
                results.put(page.getPageNumber(), node.asText());
            }
        }
        return results;
    }

    String cleanupAssistantContent(String content) {
        if (!StringUtils.hasText(content)) {
            return "{}";
        }
        String trimmed = content.trim();
        if (trimmed.startsWith("```")) {
            trimmed = trimmed.substring(trimmed.indexOf("\n") + 1);
            int closingIndex = trimmed.lastIndexOf("```\n");
            if (closingIndex >= 0) {
                trimmed = trimmed.substring(0, closingIndex);
            } else if (trimmed.endsWith("```")) {
                trimmed = trimmed.substring(0, trimmed.length() - 3);
            }
        }
        return trimmed.trim();
    }

    private void ensureAllPagesPresent(Set<Integer> translatedPages, List<PdfPageText> chunk) {
        Set<Integer> required = new LinkedHashSet<>();
        for (PdfPageText page : chunk) {
            required.add(page.getPageNumber());
        }

        if (!translatedPages.containsAll(required)) {
            Set<Integer> missing = new LinkedHashSet<>(required);
            missing.removeAll(translatedPages);
            throw new IllegalStateException("Translation response was missing pages: " + missing);
        }
    }

    enum ProviderType {
        OPENAI_COMPATIBLE("openai", "/chat/completions", "https://api.openai.com/v1"),
        OLLAMA("ollama", "/api/chat", "http://localhost:11434");

        private final String key;
        private final String endpointPath;
        private final String defaultBaseUrl;

        ProviderType(String key, String endpointPath, String defaultBaseUrl) {
            this.key = key;
            this.endpointPath = endpointPath;
            this.defaultBaseUrl = defaultBaseUrl;
        }

        public String getDefaultBaseUrl() {
            return defaultBaseUrl;
        }

        public URI resolveEndpoint(String baseUrl) {
            String normalizedBase =
                    StringUtils.hasText(baseUrl) ? baseUrl.trim() : getDefaultBaseUrl();
            if (normalizedBase.endsWith("/")) {
                normalizedBase = normalizedBase.substring(0, normalizedBase.length() - 1);
            }
            return URI.create(normalizedBase + endpointPath);
        }

        static ProviderType from(String value) {
            if (!StringUtils.hasText(value)) {
                return OPENAI_COMPATIBLE;
            }
            String normalized = value.trim().toLowerCase(Locale.ROOT);
            for (ProviderType type : values()) {
                if (Objects.equals(type.key, normalized)) {
                    return type;
                }
            }
            return OPENAI_COMPATIBLE;
        }
    }
}
