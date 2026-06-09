package me.karboom.java.iSerf.agent.billing;

import lombok.extern.slf4j.Slf4j;
import me.karboom.java.iSerf.billing.Cost;
import me.karboom.java.iSerf.billing.ILedger;
import me.karboom.java.iSerf.config.Config;
import me.karboom.java.iSerf.util.DataUtil;
import oshi.SystemInfo;

import java.time.Instant;

/**
 * Agent 计费模块
 * 封装 Agent 相关的计费逻辑，作为 Agent 与底层 billing 包之间的防腐层。
 * Agent 只需调用高层语义方法，无需直接操作 Cost 和 ILedger。
 */
@Slf4j
public class AgentBilling {
    private static final String CPU_MODEL = new SystemInfo()
            .getHardware().getProcessor().getProcessorIdentifier().getName();

    private final String agentId;
    private final ILedger ledger;

    /**
     * @param agentId Agent ID，用于标识计费目标
     * @param ledger  计费账本实现，为 null 时使用 Config 默认账本
     */
    public AgentBilling(String agentId, ILedger ledger) {
        this.agentId = agentId;
        this.ledger = ledger != null ? ledger : Config.getInstance().getDefaultLedger();
    }

    /**
     * 记录内存占用
     * @param bytes 内存占用字节数
     */
    public void recordMemory(long bytes) {
        var cost = Cost.builder()
                .id(DataUtil.getFlakeId())
                .targetType("agent")
                .targetId(agentId)
                .memory((int) bytes)
                .captureTime(Instant.now())
                .build();
        ledger.record(cost);
        log.debug("recordMemory agent: {} size: {} bytes", agentId, bytes);
    }

    /**
     * 记录 CPU 耗时
     * @param nanos CPU 耗时（纳秒）
     */
    public void recordCpu(long nanos) {
        var cost = Cost.builder()
                .id(DataUtil.getFlakeId())
                .targetType("agent")
                .targetId(agentId)
                .cpuModel(CPU_MODEL)
                .cpu(nanos)
                .captureTime(Instant.now())
                .build();
        ledger.record(cost);
        log.debug("recordCpu agent: {} cpu: {} ns", agentId, nanos);
    }

    /**
     * 记录 LLM token 消耗
     * @param tokens token 数量
     */
    public void recordToken(int tokens) {
        var cost = Cost.builder()
                .id(DataUtil.getFlakeId())
                .targetType("agent")
                .targetId(agentId)
                .token(tokens)
                .captureTime(Instant.now())
                .build();
        ledger.record(cost);
        log.debug("recordToken agent: {} tokens: {}", agentId, tokens);
    }

    /**
     * 记录存储占用
     * @param bytes 存储占用字节数
     */
    public void recordDisk(int bytes) {
        var cost = Cost.builder()
                .id(DataUtil.getFlakeId())
                .targetType("agent")
                .targetId(agentId)
                .disk(bytes)
                .captureTime(Instant.now())
                .build();
        ledger.record(cost);
        log.debug("recordDisk agent: {} disk: {} bytes", agentId, bytes);
    }

    /**
     * 记录网络流量
     * @param bytes 网络流量字节数
     */
    public void recordTraffic(int bytes) {
        var cost = Cost.builder()
                .id(DataUtil.getFlakeId())
                .targetType("agent")
                .targetId(agentId)
                .traffic(bytes)
                .captureTime(Instant.now())
                .build();
        ledger.record(cost);
        log.debug("recordTraffic agent: {} traffic: {} bytes", agentId, bytes);
    }
}
