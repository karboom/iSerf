package me.karboom.java.iSerf.schedule;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import me.karboom.java.iSerf.agent.tool.Context;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.function.BiConsumer;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Plan{
    public String id;

    public String description;

    /**
     * 执行任务的周期表达式
     */
    public String cron;
    /**
     * 执行任务的时间
     */
    public LocalDateTime time;


    /**
     * 定时任务名称，用于初始加载
     */
    public String functionName;


    /**
     * 任务参数
     */
    public Map<String, Object> functionParams;


    public BiConsumer<Context, Map<String, Object>> function;


    public Context context;
}