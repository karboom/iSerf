package me.karboom.java.iSerf.rag.scene;

import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import me.karboom.java.iSerf.agent.AgentMessage;
import me.karboom.java.iSerf.llm.embedding.IEmbedding;
import me.karboom.java.iSerf.llm.embedding.Input;
import me.karboom.java.iSerf.rag.bo.memory.MemoryExtractResult;
import me.karboom.java.iSerf.rag.bo.memory.VectorItem;
import me.karboom.java.iSerf.rag.store.IStore;
import me.karboom.java.iSerf.util.DataUtil;
import me.karboom.java.iSerf.util.JSONUtil;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 专门为大模型记忆设计的知识库服务
 */
@Slf4j
public class Memory {

    private final me.karboom.java.iSerf.llm.text.IText llm;
    private final IEmbedding embedding;
    public IStore<VectorItem> vectorStore;
    private String prompt;

    @SneakyThrows
    public Memory(me.karboom.java.iSerf.llm.text.IText llm, IEmbedding embedding, IStore<VectorItem> vectorStore) {
        this.llm = llm;
        this.embedding = embedding;
        this.vectorStore = vectorStore;

        // 从 memory_extract.md 读取提示词
        prompt = Files.readString(Paths.get("src/main/java/me/karboom/java/iSerf/rag/scene/memory_extract.md"));
    }

    /**
     * 为 VectorItem 列表生成向量
     */
    private void fillVectors(List<VectorItem> items) {
        if (items.isEmpty()) {
            return;
        }

        var inputs = items.stream()
                .map(item -> Input.builder()
                        .text(item.content)
                        .build())
                .collect(Collectors.toList());

        var output = embedding.convert(inputs);
        var results = output.getResult();

        for (int i = 0; i < items.size() && i < results.size(); i++) {
            var embedding = results.get(i).getEmbedding();
            items.get(i).setVector(embedding.stream()
                    .map(Double::floatValue)
                    .collect(Collectors.toList()));
        }
    }

    /**
     * 记忆提取功能
     * 1. message 转字符串 (使用 JSONUtil)
     * 2. 从 vectorStore 读取现有记忆
     * 3. 调用 llm 将消息提取为 ExtractResult 格式
     * 4. vector 旧数据清除，新数据写入
     */
    @SneakyThrows
    public void update(String userId, Object messages) {
        log.debug("update: userId={}, messages={}", userId, messages);

        var messageText = JSONUtil.stringify(messages);

        // 从 vectorStore 读取现有记忆 - 通过 userId 过滤获取该用户的所有记忆
        var existingMemories = vectorStore.mixedSearch(Map.of("userId", userId));

        // 构建提示词
        var existingMemoryText = existingMemories != null && !existingMemories.isEmpty() ?
                existingMemories.stream()
                        .map(m -> "- [%s] %s".formatted(m.category, m.content))
                        .collect(Collectors.joining("\n")) :
                "暂无现有记忆";

        var fullPrompt = """
                %s

                ## 现有记忆
                %s

                ## 对话内容
                %s

                """.formatted(prompt, existingMemoryText, messageText);

        // 调用 llm 提取记忆
        var messageList = List.of(
                AgentMessage.builder()
                        .role(AgentMessage.ROLE.USER)
                        .type(AgentMessage.TYPE.TEXT)
                        .text(fullPrompt)
                        .build()
        );

        var llmOutput = llm.query(messageList, MemoryExtractResult.class);
        var text = llmOutput.getChoices().getFirst().getText();
        var extractResult = JSONUtil.parse(text, MemoryExtractResult.class);

        if (extractResult == null || extractResult.items == null || extractResult.items.isEmpty()) {
            log.debug("update: No items extracted");
            return;
        }

        // 为每个 message 生成 id 并填充向量
        var newVectorItems = extractResult.items.stream()
                .map(item -> VectorItem.builder()
                        .userId(userId)
                        .content(item.content)
                        .category(item.category)
                        .sourceQuote(item.sourceQuote)
                        .build())
                .peek(vectorItem -> vectorItem.setId(DataUtil.getFlakeId()))
                .collect(Collectors.toList());
        fillVectors(newVectorItems);

        // 删除旧数据
        if (existingMemories != null && !existingMemories.isEmpty()) {
            var oldIds = existingMemories.stream()
                    .map(VectorItem::getId)
                    .collect(Collectors.toList());
            vectorStore.deleteByIds(oldIds);
        }

        // 写入新数据
        if (!newVectorItems.isEmpty()) {
            vectorStore.create(newVectorItems);
            log.debug("update: Created {} new memory items", newVectorItems.size());
        }
    }


    /**
     * 根据用户提问，找出有用的知识
     * @param userId
     * @param input
     */
    @SneakyThrows
    public List<VectorItem> match(String userId, String input) {
        log.debug("match: userId={}, input={}", userId, input);

        // 为输入生成向量
        var inputs = List.of(Input.builder().text(input).build());
        var output = embedding.convert(inputs);
        var embeddingData = output.getResult().getFirst().getEmbedding();
        if (embeddingData == null || embeddingData.isEmpty()) {
            return List.of();
        }
        var vector = embeddingData.stream()
                .map(Double::floatValue)
                .collect(Collectors.toList());

        // 构建混合查询条件：userId 过滤 + 向量相似搜索
        var query = new HashMap<String, Object>();
        query.put("userId", userId);
        query.put("content|ann", vector);

        // 执行混合搜索
        var results = vectorStore.mixedSearch(query);
        log.debug("match: Found {} matching memories", results != null ? results.size() : 0);

        return results != null ? results : List.of();
    }
}