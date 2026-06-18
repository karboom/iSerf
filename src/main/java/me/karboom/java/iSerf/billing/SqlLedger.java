package me.karboom.java.iSerf.billing;

import lombok.extern.slf4j.Slf4j;
import me.karboom.java.iSerf.agent.Agent;
import me.karboom.java.iSerf.util.DataUtil;
import oshi.SystemInfo;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Record;
import org.jooq.Table;

import java.time.Instant;
import java.util.List;

import static org.jooq.impl.DSL.*;
import static org.jooq.impl.SQLDataType.*;

@Slf4j
public class SqlLedger implements ILedger {
    private static final String CPU_MODEL = new SystemInfo()
            .getHardware().getProcessor().getProcessorIdentifier().getName();

    // region ========== 表定义 ==========

    private static final Table<Record> COST_TABLE = table("billing_cost");

    private static final Field<String> F_ID = field("id", VARCHAR(64));
    private static final Field<String> F_USER_ID = field("user_id", VARCHAR(64));
    private static final Field<String> F_TARGET_TYPE = field("target_type", VARCHAR(64));
    private static final Field<String> F_TARGET_ID = field("target_id", VARCHAR(64));
    private static final Field<String> F_USAGE = field("usage", VARCHAR(128));
    private static final Field<Instant> F_CAPTURE_TIME = field("capture_time", INSTANT);
    private static final Field<String> F_CPU_MODEL = field("cpu_model", VARCHAR(128));
    private static final Field<Long> F_CPU = field("cpu", BIGINT);
    private static final Field<Integer> F_MEMORY = field("memory", INTEGER);
    private static final Field<Integer> F_DISK = field("disk", INTEGER);
    private static final Field<Integer> F_TOKEN = field("token", INTEGER);
    private static final Field<Integer> F_TRAFFIC = field("traffic", INTEGER);

    // endregion

    private final DSLContext dsl;

    public SqlLedger(DSLContext dsl) {
        this.dsl = dsl;
    }

    /**
     * 创建表（如果不存在）
     */
    public void createTableIfNotExists() {
        dsl.execute("""
                CREATE TABLE IF NOT EXISTS billing_cost (
                    id VARCHAR(64) PRIMARY KEY,
                    user_id VARCHAR(64),
                    target_type VARCHAR(64),
                    target_id VARCHAR(64),
                    usage VARCHAR(128),
                    capture_time TIMESTAMP,
                    cpu_model VARCHAR(128),
                    cpu BIGINT,
                    memory INTEGER,
                    disk INTEGER,
                    token INTEGER,
                    traffic INTEGER
                )
                """);
        log.debug("createTableIfNotExists billing_cost table created/verified");
    }

    @Override
    public void record(Cost cost) {
        dsl.insertInto(COST_TABLE)
                .set(F_ID, cost.id)
                .set(F_USER_ID, cost.userId)
                .set(F_TARGET_TYPE, cost.targetType)
                .set(F_TARGET_ID, cost.targetId)
                .set(F_USAGE, cost.usage)
                .set(F_CAPTURE_TIME, cost.captureTime)
                .set(F_CPU_MODEL, cost.cpuModel)
                .set(F_CPU, cost.cpu)
                .set(F_MEMORY, cost.memory)
                .set(F_DISK, cost.disk)
                .set(F_TOKEN, cost.token)
                .set(F_TRAFFIC, cost.traffic)
                .execute();
        log.debug("record Cost: {} -> billing_cost", cost.id);
    }

    @Override
    public List<Cost> query(String targetType, String targetId) {
        var result = dsl.select()
                .from(COST_TABLE)
                .where(F_TARGET_TYPE.eq(targetType))
                .and(F_TARGET_ID.eq(targetId))
                .fetch()
                .map(this::recordToCost);
        log.debug("query targetType: {} targetId: {} found: {}", targetType, targetId, result.size());
        return result;
    }

    @Override
    public List<Cost> queryByUser(String userId) {
        var result = dsl.select()
                .from(COST_TABLE)
                .where(F_USER_ID.eq(userId))
                .fetch()
                .map(this::recordToCost);
        log.debug("queryByUser userId: {} found: {}", userId, result.size());
        return result;
    }

    // region ========== 语义方法 ==========

    @Override
    public void recordMemory(Agent agent, long bytes) {
        record(Cost.builder()
                .id(DataUtil.getFlakeId())
                .targetType("agent")
                .targetId(agent.metadata.getId())
                .memory((int) bytes)
                .captureTime(Instant.now())
                .build());
    }

    @Override
    public void recordCpu(Agent agent, long nanos) {
        record(Cost.builder()
                .id(DataUtil.getFlakeId())
                .targetType("agent")
                .targetId(agent.metadata.getId())
                .cpuModel(CPU_MODEL)
                .cpu(nanos)
                .captureTime(Instant.now())
                .build());
    }

    @Override
    public void recordToken(Agent agent, int tokens) {
        record(Cost.builder()
                .id(DataUtil.getFlakeId())
                .targetType("agent")
                .targetId(agent.metadata.getId())
                .token(tokens)
                .captureTime(Instant.now())
                .build());
    }

    @Override
    public void recordDisk(Agent agent, int bytes) {
        record(Cost.builder()
                .id(DataUtil.getFlakeId())
                .targetType("agent")
                .targetId(agent.metadata.getId())
                .disk(bytes)
                .captureTime(Instant.now())
                .build());
    }

    @Override
    public void recordTraffic(Agent agent, int bytes) {
        record(Cost.builder()
                .id(DataUtil.getFlakeId())
                .targetType("agent")
                .targetId(agent.metadata.getId())
                .traffic(bytes)
                .captureTime(Instant.now())
                .build());
    }

    @Override
    public long usageCount(Agent agent) {
        var count = dsl.selectCount()
                .from(COST_TABLE)
                .where(F_TARGET_TYPE.eq("agent"))
                .and(F_TARGET_ID.eq(agent.metadata.getId()))
                .fetchOne(0, long.class);
        return count != null ? count : 0L;
    }

    @Override
    public long totalTokens(Agent agent) {
        var sum = dsl.select(sum(F_TOKEN))
                .from(COST_TABLE)
                .where(F_TARGET_TYPE.eq("agent"))
                .and(F_TARGET_ID.eq(agent.metadata.getId()))
                .fetchOne(0, long.class);
        return sum != null ? sum : 0L;
    }

    // endregion

    // region ========== 用户维度统计 ==========

    @Override
    public UsageSummary queryUsageSummary(String userId) {
        var result = dsl.select(
                        count().as("count"),
                        sum(F_CPU).as("cpu"),
                        sum(F_MEMORY).as("memory"),
                        sum(F_DISK).as("disk"),
                        sum(F_TOKEN).as("token"),
                        sum(F_TRAFFIC).as("traffic"))
                .from(COST_TABLE)
                .where(F_USER_ID.eq(userId))
                .fetchOne();

        if (result == null) {
            return UsageSummary.builder()
                    .count(0L)
                    .cpu(0L)
                    .memory(0L)
                    .disk(0L)
                    .token(0L)
                    .traffic(0L)
                    .build();
        }

        return UsageSummary.builder()
                .count(result.get("count", long.class))
                .cpu(nullToZero(result.get("cpu", Long.class)))
                .memory(nullToZero(result.get("memory", Long.class)))
                .disk(nullToZero(result.get("disk", Long.class)))
                .token(nullToZero(result.get("token", Long.class)))
                .traffic(nullToZero(result.get("traffic", Long.class)))
                .build();
    }

    // endregion

    // region ========== 私有方法 ==========

    private Cost recordToCost(Record record) {
        return Cost.builder()
                .id(record.get(F_ID))
                .userId(record.get(F_USER_ID))
                .targetType(record.get(F_TARGET_TYPE))
                .targetId(record.get(F_TARGET_ID))
                .usage(record.get(F_USAGE))
                .captureTime(record.get(F_CAPTURE_TIME))
                .cpuModel(record.get(F_CPU_MODEL))
                .cpu(record.get(F_CPU))
                .memory(record.get(F_MEMORY))
                .disk(record.get(F_DISK))
                .token(record.get(F_TOKEN))
                .traffic(record.get(F_TRAFFIC))
                .build();
    }

    private Long nullToZero(Long value) {
        return value != null ? value : 0L;
    }

    // endregion
}
