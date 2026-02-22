package me.karboom.java.iSerf.llm.embedding;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Output {
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    static public class Usage {
        public Integer inputTokens;
        public Integer totalTokens;
        public Integer imageTokens;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    static public class Result{
        public String type;
        public List<Double> embedding;
    }


    public Usage usage;
    public List<Result> result;

}
