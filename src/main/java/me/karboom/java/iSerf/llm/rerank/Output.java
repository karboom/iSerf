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
public class Output {

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    static public class Result {
        public Integer index;
        public String score;
        public Input.Item input;
    }


    public List<Result> list;

}
