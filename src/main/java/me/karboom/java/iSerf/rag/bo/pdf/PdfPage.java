package me.karboom.java.iSerf.rag.bo.pdf;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * PDF 页面
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PdfPage {
    private Integer physicalIndex;               // 物理页码 X (对应<physical_index_X>)
    private String content;                      // 页面文本内容
    private Integer tokenCount;                  // token 数量
    private PdfPageAnalysis analysis;               // LLM 一次性提取的结果
}
