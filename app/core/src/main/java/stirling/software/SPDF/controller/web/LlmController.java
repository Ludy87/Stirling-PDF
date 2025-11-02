package stirling.software.SPDF.controller.web;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

import io.swagger.v3.oas.annotations.Hidden;
import io.swagger.v3.oas.annotations.tags.Tag;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import stirling.software.common.model.ApplicationProperties;

@Controller
@Tag(name = "LLM", description = "LLM APIs")
@RequiredArgsConstructor
@Slf4j
public class LlmController {

    private final ApplicationProperties applicationProperties;

    @GetMapping("/translate-pdf")
    @Hidden
    public String translatePdf(Model model) {
        model.addAttribute("currentPage", "translate-pdf");
        model.addAttribute("llmConfig", applicationProperties.getSystem().getLlm());
        return "llm/translate-pdf";
    }
}
