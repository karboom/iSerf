package me.karboom.java.iSerf.rag.bo.pdf;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * PDF 页面分析结果 - LLM 一次性提取的所有信息
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PdfPageAnalysis {
    private String isTocPage;                    // "yes"/"no" - 该页是否为目录页
    private List<PdfTocItem> tocItems;              // 从该页提取的目录项列表
    private List<PdfSectionStart> sectionStarts;    // 该页开始的章节列表
}
