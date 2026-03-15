package me.karboom.java.iSerf.llm.rerank;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Dashscope Rerank 模型测试类
 */
public class DashscopeTest {

    /**
     * 创建 Dashscope 实例的辅助方法
     */
    private Dashscope createDashscope(String model) {
        var apiKey = System.getenv("OPENAI_API_KEY");

        var config = new HashMap<String, Object>();

        return new Dashscope(model, config, apiKey, null);
    }

    @Test
    void testTextRank() {
        assertTimeoutPreemptively(Duration.ofSeconds(30), () -> {
            var dashscope = createDashscope("qwen3-rerank");

            var query = "什么是人工智能";
            var documents = List.of(
                Input.Item.builder().content("人工智能是研究、开发用于模拟、延伸和扩展人的智能的理论、方法、技术及应用系统的一门新的技术科学").build(),
                Input.Item.builder().content("机器学习是人工智能的核心领域，主要研究计算机如何模拟或实现人类的学习行为").build(),
                Input.Item.builder().content("深度学习是机器学习的一个分支，通过多层神经网络实现复杂模式识别").build(),
                Input.Item.builder().content("今天天气真好，适合出去散步").build()
            );

            var input = Input.builder()
                .query(query)
                .documents(documents)
                .topN(3)
                .build();

            var output = dashscope.rank(input);
            assertNotNull(output);
            assertNotNull(output.getList());
            assertFalse(output.getList().isEmpty());

            for (var result : output.getList()) {
                System.out.println("索引：" + result.getIndex() + "，分数：" + result.getScore() + "，内容：" + result.getInput().getContent());
            }
        });
    }

    @Test
    void testMultimodalRank() {
        assertTimeoutPreemptively(Duration.ofSeconds(30), () -> {
            var dashscope = createDashscope("qwen3-vl-rerank");

            var query = "什么是文本排序模型";
            var documents = List.of(
                Input.Item.builder().type("text").content("文本排序模型广泛用于搜索引擎和推荐系统中，它们根据文本相关性对候选文本进行排序").build(),
                Input.Item.builder().type("image").content("https://img.alicdn.com/imgextra/i3/O1CN01rdstgY1uiZWt8gqSL_!!6000000006071-0-tps-1970-356.jpg").build(),
                Input.Item.builder().type("video").content("https://help-static-aliyun-doc.aliyuncs.com/file-manage-files/zh-CN/20250107/lbcemt/new+video.mp4").build()
            );

            var input = Input.builder()
                .query(query)
                .documents(documents)
                .topN(2)
                .build();

            var output = dashscope.rank(input);
            assertNotNull(output);
            assertNotNull(output.getList());
            assertFalse(output.getList().isEmpty());

            for (var result : output.getList()) {
                System.out.println("类型：" + result.getInput().getType() + "，索引：" + result.getIndex() + "，分数：" + result.getScore() + "，内容：" + result.getInput().getContent());
            }
        });
    }
}