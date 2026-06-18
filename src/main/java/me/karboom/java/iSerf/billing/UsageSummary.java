package me.karboom.java.iSerf.billing;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UsageSummary {
    /**
     * 使用次数
     */
    private Long count;

    /**
     * CPU运行时间ns
     */
    private Long cpu;

    /**
     * 内存占用byte
     */
    private Long memory;

    /**
     * 存储占用byte
     */
    private Long disk;

    /**
     * 大模型token消耗
     */
    private Long token;

    /**
     * 网络上行流量byte
     */
    private Long traffic;
}
