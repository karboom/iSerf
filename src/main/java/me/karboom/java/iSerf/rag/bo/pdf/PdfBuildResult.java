package me.karboom.java.iSerf.rag.bo.pdf;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 构建结果
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PdfBuildResult {
    private String docName;
    private String docDescription;
    private List<PdfTreeNode> structure;
}
