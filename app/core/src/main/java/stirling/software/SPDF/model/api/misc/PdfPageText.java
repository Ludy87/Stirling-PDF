package stirling.software.SPDF.model.api.misc;

import lombok.Value;

@Value
public class PdfPageText {
    int pageNumber;
    String text;

    public boolean hasContent() {
        return text != null && !text.trim().isEmpty();
    }
}
