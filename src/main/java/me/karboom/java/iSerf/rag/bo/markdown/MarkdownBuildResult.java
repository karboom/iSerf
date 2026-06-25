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
public class MarkdownBuildResult {
    private String docName;
    private String docDescription;
    private List<MarkdownTreeNode> structure;
}
