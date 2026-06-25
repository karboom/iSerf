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
public class VectorItem {
    public String id;
    public String userId;

    public String content;
    public String category;
    public String sourceQuote;

    public List<Float> vector;
}
