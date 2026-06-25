package me.karboom.java.iSerf.rag.bo.markdown;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MarkdownTreeNode {
    private String title;
    private Integer lineNum;
    private Integer level;
    private String text;
    private String nodeId;
    private Integer textTokenCount;
    private String summary;
    private List<MarkdownTreeNode> nodes;
}
