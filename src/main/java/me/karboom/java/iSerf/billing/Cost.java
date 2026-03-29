package me.karboom.java.iSerf.billing;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class Cost {
    public String id;

    public String targetType;
    public String targetId;
    public String usage;

    public LocalDateTime startTime;
    public LocalDateTime endTime;

    /**
     * CPU运行时间us
     */
    public Integer cpu;
    /**
     * 内存占用byte
     */
    public Integer memory;
    /**
     * 存储占用byte
     */
    public Integer disk;
    /**
     * 大模型token消耗
     */
    public Integer token;

    /**
     * 网络上行流量byte
     */
    public Integer traffic;
}
