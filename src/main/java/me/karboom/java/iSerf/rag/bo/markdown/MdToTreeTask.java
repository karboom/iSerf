package me.karboom.java.iSerf.rag.bo.markdown;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MdToTreeTask {

    public static class Status {
        public static final String PENDING = "PENDING";
        public static final String RUNNING = "RUNNING";
        public static final String COMPLETED = "COMPLETED";
        public static final String FAILED = "FAILED";
        public static final String CANCELLED = "CANCELLED";
    }

    private String id;
    private String mdPath;
    private String status;
    private String stage;
    private Double progress;
    private String message;
    private MarkdownBuildResult result;
    private String errorMessage;
    private MarkdownBuildOptions options;
}
