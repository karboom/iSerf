package me.karboom.java.iSerf.agent.billing;

import me.karboom.java.iSerf.agent.Agent;
import me.karboom.java.iSerf.agent.AgentMetadata;
import me.karboom.java.iSerf.billing.Cost;
import me.karboom.java.iSerf.billing.ILedger;
import me.karboom.java.iSerf.billing.UsageSummary;
import me.karboom.java.iSerf.util.DataUtil;
import oshi.SystemInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AgentBillingTest {

    private static final String CPU_MODEL = new SystemInfo()
            .getHardware().getProcessor().getProcessorIdentifier().getName();

    private List<Cost> recordedCosts;
    private ILedger testLedger;
    private Agent testAgent;

    @BeforeEach
    void setUp() {
        recordedCosts = new ArrayList<>();
        testLedger = new ILedger() {
            @Override
            public void record(Cost cost) {
                recordedCosts.add(cost);
            }

            @Override
            public List<Cost> query(String targetType, String targetId) {
                return recordedCosts.stream()
                        .filter(c -> targetType.equals(c.getTargetType()) && targetId.equals(c.getTargetId()))
                        .toList();
            }

            @Override
            public List<Cost> queryByUser(String userId) {
                return recordedCosts.stream()
                        .filter(c -> userId.equals(c.getUserId()))
                        .toList();
            }

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
                return query("agent", agent.metadata.getId()).size();
            }

            @Override
            public long totalTokens(Agent agent) {
                return query("agent", agent.metadata.getId()).stream()
                        .filter(c -> c.getToken() != null)
                        .mapToLong(Cost::getToken)
                        .sum();
            }

            @Override
            public UsageSummary queryUsageSummary(String userId) {
                var costs = queryByUser(userId);
                return UsageSummary.builder()
                        .count((long) costs.size())
                        .cpu(costs.stream().filter(c -> c.getCpu() != null).mapToLong(Cost::getCpu).sum())
                        .memory(costs.stream().filter(c -> c.getMemory() != null).mapToLong(Cost::getMemory).sum())
                        .disk(costs.stream().filter(c -> c.getDisk() != null).mapToLong(Cost::getDisk).sum())
                        .token(costs.stream().filter(c -> c.getToken() != null).mapToLong(Cost::getToken).sum())
                        .traffic(costs.stream().filter(c -> c.getTraffic() != null).mapToLong(Cost::getTraffic).sum())
                        .build();
            }
        };
        testAgent = new Agent();
        var metadata = new AgentMetadata();
        metadata.setId("test-agent-001");
        testAgent.metadata = metadata;
    }

    // region ========== recordMemory ==========

    @Test
    void testRecordMemory() {
        testLedger.recordMemory(testAgent, 1024L);

        assertEquals(1, recordedCosts.size());
        var cost = recordedCosts.get(0);
        assertNotNull(cost.getId());
        assertEquals("agent", cost.getTargetType());
        assertEquals("test-agent-001", cost.getTargetId());
        assertEquals(1024, (int) cost.getMemory());
        assertNotNull(cost.getCaptureTime());
    }

    // endregion

    // region ========== recordCpu ==========

    @Test
    void testRecordCpu() {
        testLedger.recordCpu(testAgent, 500_000L);

        assertEquals(1, recordedCosts.size());
        var cost = recordedCosts.get(0);
        assertNotNull(cost.getId());
        assertEquals("agent", cost.getTargetType());
        assertEquals("test-agent-001", cost.getTargetId());
        assertEquals(500_000L, (long) cost.getCpu());
        assertNotNull(cost.getCpuModel());
        assertNotNull(cost.getCaptureTime());
    }

    // endregion

    // region ========== recordToken ==========

    @Test
    void testRecordToken() {
        testLedger.recordToken(testAgent, 2500);

        assertEquals(1, recordedCosts.size());
        var cost = recordedCosts.get(0);
        assertNotNull(cost.getId());
        assertEquals("agent", cost.getTargetType());
        assertEquals("test-agent-001", cost.getTargetId());
        assertEquals(2500, (int) cost.getToken());
        assertNotNull(cost.getCaptureTime());
    }

    // endregion

    // region ========== recordDisk ==========

    @Test
    void testRecordDisk() {
        testLedger.recordDisk(testAgent, 4096);

        assertEquals(1, recordedCosts.size());
        var cost = recordedCosts.get(0);
        assertNotNull(cost.getId());
        assertEquals("agent", cost.getTargetType());
        assertEquals("test-agent-001", cost.getTargetId());
        assertEquals(4096, (int) cost.getDisk());
        assertNotNull(cost.getCaptureTime());
    }

    // endregion

    // region ========== recordTraffic ==========

    @Test
    void testRecordTraffic() {
        testLedger.recordTraffic(testAgent, 8192);

        assertEquals(1, recordedCosts.size());
        var cost = recordedCosts.get(0);
        assertNotNull(cost.getId());
        assertEquals("agent", cost.getTargetType());
        assertEquals("test-agent-001", cost.getTargetId());
        assertEquals(8192, (int) cost.getTraffic());
        assertNotNull(cost.getCaptureTime());
    }

    // endregion

    // region ========== 多次记录 ==========

    @Test
    void testMultipleRecords() {
        testLedger.recordMemory(testAgent, 512L);
        testLedger.recordToken(testAgent, 100);
        testLedger.recordCpu(testAgent, 1000L);

        assertEquals(3, recordedCosts.size());
    }

    // endregion

    // region ========== usageCount ==========

    @Test
    void testUsageCount() {
        testLedger.recordMemory(testAgent, 512L);
        testLedger.recordToken(testAgent, 100);
        testLedger.recordCpu(testAgent, 1000L);

        var count = testLedger.usageCount(testAgent);

        assertEquals(3, count);
    }

    // endregion

    // region ========== totalTokens ==========

    @Test
    void testTotalTokens() {
        testLedger.recordToken(testAgent, 100);
        testLedger.recordToken(testAgent, 200);
        testLedger.recordToken(testAgent, 300);

        var total = testLedger.totalTokens(testAgent);

        assertEquals(600, total);
    }

    // endregion
}
