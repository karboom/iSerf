package me.karboom.java.iSerf.rag.bo.markdown;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class MarkdownBuildOptions {
    private Boolean ifThinning;
    private Integer minTokenThreshold;
    private Boolean ifAddNodeSummary;
    private Integer summaryTokenThreshold;
    private Boolean ifAddNodeText;
    private Boolean ifAddNodeId;
    private Boolean ifAddDocDescription;
    private String modelName;
}
