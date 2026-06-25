package me.karboom.java.iSerf.rag.bo.pdf;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 构建选项
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PdfBuildOptions {
    private Boolean ifAddNodeSummary;
    private Integer summaryTokenThreshold;
    private Boolean ifAddNodeText;
    private Boolean ifAddNodeId;
    private Boolean ifAddDocDescription;
    private String modelName;
    private Integer tocCheckPageNum;             // 最多检查多少页目录
}
