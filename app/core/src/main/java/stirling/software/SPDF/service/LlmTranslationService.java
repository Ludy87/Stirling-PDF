package stirling.software.SPDF.service;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.springframework.ai.model.NoopApiKey;
import org.springframework.ai.ollama.api.OllamaApi;
import org.springframework.ai.ollama.api.OllamaApi.ChatRequest;
import org.springframework.ai.ollama.api.OllamaApi.Message;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.ai.openai.api.OpenAiApi.ChatCompletion;
import org.springframework.ai.openai.api.OpenAiApi.ChatCompletionMessage;
import org.springframework.ai.openai.api.OpenAiApi.ChatCompletionRequest;
import org.springframework.ai.openai.api.ResponseFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.WebClient;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
            return normalizeBaseUrl(config.getBaseUrl());
        }
        return normalizeBaseUrl(provider.getDefaultBaseUrl());
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

        ObjectNode chunkContent = objectMapper.createObjectNode();
        for (PdfPageText page : chunk) {
            chunkContent.put(String.valueOf(page.getPageNumber()), page.getText());
        }

        String chunkPayload = objectMapper.writeValueAsString(chunkContent);

        String assistantContent =
                switch (provider) {
                    case OPENAI_COMPATIBLE ->
                            callOpenAi(
                                    chunkPayload,
                                    baseUrl,
                                    model,
                                    prompt,
                                    config,
                                    timeout,
                                    temperature);
                    case OLLAMA ->
                            callOllama(chunkPayload, baseUrl, model, prompt, timeout, temperature);
                };

        Map<Integer, String> parsed = parseTranslationResponse(assistantContent, chunk);
        ensureAllPagesPresent(parsed.keySet(), chunk);
        return parsed;
    }

    private String callOpenAi(
            String chunkPayload,
            String baseUrl,
            String model,
            String prompt,
            ApplicationProperties.System.Llm config,
            Duration timeout,
            double temperature) {

        List<ChatCompletionMessage> messages = new ArrayList<>();
        messages.add(new ChatCompletionMessage(prompt, ChatCompletionMessage.Role.SYSTEM));
        messages.add(new ChatCompletionMessage(chunkPayload, ChatCompletionMessage.Role.USER));

        ResponseFormat responseFormat =
                ResponseFormat.builder().type(ResponseFormat.Type.JSON_OBJECT).build();

        OpenAiApi.Builder builder =
                OpenAiApi.builder()
                        .baseUrl(baseUrl)
                        .completionsPath(ProviderType.OPENAI_COMPATIBLE.getEndpointPath())
                        .restClientBuilder(createRestClientBuilder(timeout))
                        .webClientBuilder(createWebClientBuilder());

        if (StringUtils.hasText(config.getApiKey())) {
            builder.apiKey(config.getApiKey().trim());
        } else {
            builder.apiKey(new NoopApiKey());
        }

        OpenAiApi api = builder.build();

        ChatCompletionRequest request =
                new ChatCompletionRequest(
                        messages,
                        model,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        responseFormat,
                        null,
                        null,
                        null,
                        false,
                        null,
                        temperature,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null);

        ResponseEntity<ChatCompletion> response = api.chatCompletionEntity(request);
        ChatCompletion completion = response.getBody();
        if (completion == null || completion.choices() == null || completion.choices().isEmpty()) {
            throw new IllegalStateException("Translation response did not include choices");
        }

        ChatCompletionMessage assistantMessage = completion.choices().get(0).message();
        if (assistantMessage == null || !StringUtils.hasText(assistantMessage.content())) {
            throw new IllegalStateException(
                    "Translation response did not include assistant content");
        }

        return assistantMessage.content();
    }

    private String callOllama(
            String chunkPayload,
            String baseUrl,
            String model,
            String prompt,
            Duration timeout,
            double temperature) {

        List<Message> messages = new ArrayList<>();
        messages.add(Message.builder(Message.Role.SYSTEM).content(prompt).build());
        messages.add(Message.builder(Message.Role.USER).content(chunkPayload).build());

        Map<String, Object> options = new LinkedHashMap<>();
        options.put("temperature", temperature);

        ChatRequest request =
                ChatRequest.builder(model)
                        .messages(messages)
                        .format("json")
                        .options(options)
                        .build();

        OllamaApi api =
                OllamaApi.builder()
                        .baseUrl(baseUrl)
                        .restClientBuilder(createRestClientBuilder(timeout))
                        .webClientBuilder(createWebClientBuilder())
                        .build();

        Message assistantMessage = api.chat(request).message();
        if (assistantMessage == null || !StringUtils.hasText(assistantMessage.content())) {
            throw new IllegalStateException(
                    "Translation response did not include assistant content");
        }
        return assistantMessage.content();
    }

    private RestClient.Builder createRestClientBuilder(Duration timeout) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        int millis = (int) Math.min(Integer.MAX_VALUE, timeout.toMillis());
        requestFactory.setConnectTimeout(millis);
        requestFactory.setReadTimeout(millis);
        return RestClient.builder().requestFactory(requestFactory);
    }

    private WebClient.Builder createWebClientBuilder() {
        return WebClient.builder();
    }

    private String normalizeBaseUrl(String baseUrl) {
        if (!StringUtils.hasText(baseUrl)) {
            return "";
        }

        String normalized = baseUrl.trim();
        if (normalized.endsWith("/")) {
            return normalized.substring(0, normalized.length() - 1);
        }

        return normalized;
    }

    Map<Integer, String> parseTranslationResponse(String content, List<PdfPageText> chunk)
            throws IOException {
        String cleaned = cleanupAssistantContent(content);
        JsonNode root = objectMapper.readTree(cleaned);
        if (!root.isObject()) {
            throw new IllegalStateException("Translation response was not a JSON object");
        }

        Set<Integer> present = new LinkedHashSet<>();
        root.fieldNames()
                .forEachRemaining(
                        fn -> {
                            try {
                                present.add(Integer.parseInt(fn));
                            } catch (NumberFormatException ignore) {
                            }
                        });

        Map<Integer, String> results = new LinkedHashMap<>();
        for (PdfPageText page : chunk) {
            JsonNode node = root.get(String.valueOf(page.getPageNumber()));
            if (node != null && !node.isNull()) {
                results.put(page.getPageNumber(), node.asText());
            } else {
                results.put(page.getPageNumber(), "");
            }
        }

        ensureAllPagesPresentFromJson(present, chunk);
        return results;
    }

    private void ensureAllPagesPresentFromJson(Set<Integer> present, List<PdfPageText> chunk) {
        Set<Integer> required = new LinkedHashSet<>();
        for (PdfPageText page : chunk) required.add(page.getPageNumber());
        if (!present.containsAll(required)) {
            Set<Integer> missing = new LinkedHashSet<>(required);
            missing.removeAll(present);
            throw new IllegalStateException("Translation response was missing pages: " + missing);
        }
    }

    String cleanupAssistantContent(String content) {
        if (!StringUtils.hasText(content)) {
            return "{}";
        }
        String trimmed = content.trim();
        if (trimmed.startsWith("```")) {
            int firstNl = trimmed.indexOf('\n');
            if (firstNl > 0) {
                trimmed = trimmed.substring(firstNl + 1);
            }
        }
        if (trimmed.endsWith("```")) {
            trimmed = trimmed.substring(0, trimmed.length() - 3);
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

        public String getEndpointPath() {
            return endpointPath;
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
