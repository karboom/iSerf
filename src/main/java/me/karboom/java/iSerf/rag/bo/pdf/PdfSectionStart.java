package me.karboom.java.iSerf.rag.bo.pdf;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 章节起始信息
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PdfSectionStart {
    private String structure;                    // 层级结构 "1.2.3"
    private String title;                        // 章节标题
    private Integer physicalIndex;               // 章节起始物理页码
}
