package me.karboom.java.iSerf.rag.bo.agentic;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class IndexHit {
    public String thinking;
    public List<String> nodeIds;
}
