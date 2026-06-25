package me.karboom.java.iSerf.rag.bo.pdf;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 树节点
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PdfTreeNode {
    private String title;
    private String structure;                    // 层级结构 "1.2.3"
    private Integer physicalIndex;               // 起始物理页码
    private String text;                         // 节点文本内容
    private String nodeId;
    private Integer textTokenCount;
    private String summary;
    private List<PdfTreeNode> nodes;                // 子节点
}
