package me.karboom.java.iSerf.agent.tool;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import tools.jackson.databind.node.ObjectNode;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CallResult {
    /**
     * 告诉大模型的结果
     */
    public String llm;

    /**
     * 错误信息
     */
    public RuntimeException error;

    /**
     * 直接输出的调用结果
     */
    public ObjectNode direct;


}
