package me.karboom.java.iSerf.rag.bo.pdf;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 目录项
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PdfTocItem {
    private String structure;                    // 层级结构 "1.2.3"
    private String title;                        // 章节标题
    private Integer page;                        // 目录中记录的页码 (如果有)
    private Integer physicalIndex;               // 实际物理页码 (从标签提取)
}
