package me.karboom.java.iSerf.llm.embedding;


import java.util.List;
import java.util.Map;

public interface IEmbedding {

    Output convert(List<Input> input);


    /**
     * 批量装换向量
     * @param input
     * @return 异步任务ID
     */
    String batchConvert(List<Input> input);
}
