package me.karboom.java.iSerf.rag.bo.pic;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PicBuildResult {
    public String docName;
    public String docDescription;
    public List<PicTreeNode> structure;
}
