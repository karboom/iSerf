package me.karboom.java.iSerf.llm.rerank;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Input {

    @Data
    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    static public class Item {
        public String type;
        public String content;
    }

    /**
     * 查询文本
     */
    public String query;

    /**
     * 待排序的文档列表
     */
    public List<Item> documents;

    /**
     * 返回前 N 个结果
     */
    public Integer topN;

}