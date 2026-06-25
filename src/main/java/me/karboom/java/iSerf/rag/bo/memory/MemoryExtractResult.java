package me.karboom.java.iSerf.rag.bo.memory;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class MemoryExtractResult {

    @Data
    @AllArgsConstructor
    @NoArgsConstructor
    @Builder
    public static class Item {
        public String content;
        public String category;
        public List<String> tags;
        public String sourceQuote;
    }

    public List<Item> items;
}
