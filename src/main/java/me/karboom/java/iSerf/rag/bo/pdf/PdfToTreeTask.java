package me.karboom.java.iSerf.rag.bo.pdf;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 任务状态
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PdfToTreeTask {
    public static class Status {
        public static final String PENDING = "PENDING";
        public static final String RUNNING = "RUNNING";
        public static final String COMPLETED = "COMPLETED";
        public static final String FAILED = "FAILED";
    }

    private String id;
    private String pdfPath;
    private String status;
    private String stage;
    private Double progress;
    private String message;
    private PdfBuildResult result;
    private String errorMessage;
    private PdfBuildOptions options;
}
