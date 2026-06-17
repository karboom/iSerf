package me.karboom.java.iSerf.agent.billing;

import me.karboom.java.iSerf.agent.Agent;
import me.karboom.java.iSerf.agent.AgentMetadata;
import me.karboom.java.iSerf.billing.Cost;
import me.karboom.java.iSerf.billing.ILedger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AgentBillingTest {

    private List<Cost> recordedCosts;
    private ILedger testLedger;
    private AgentBilling billing;
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
        };
        billing = new AgentBilling(testLedger);
        testAgent = new Agent();
        var metadata = new AgentMetadata();
        metadata.setId("test-agent-001");
        testAgent.metadata = metadata;
    }

    // region ========== recordMemory ==========

    @Test
    void testRecordMemory() {
        billing.recordMemory(testAgent, 1024L);

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
        billing.recordCpu(testAgent, 500_000L);

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
        billing.recordToken(testAgent, 2500);

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
        billing.recordDisk(testAgent, 4096);

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
        billing.recordTraffic(testAgent, 8192);

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
        billing.recordMemory(testAgent, 512L);
        billing.recordToken(testAgent, 100);
        billing.recordCpu(testAgent, 1000L);

        assertEquals(3, recordedCosts.size());
    }

    // endregion

    // region ========== usageCount ==========

    @Test
    void testUsageCount() {
        billing.recordMemory(testAgent, 512L);
        billing.recordToken(testAgent, 100);
        billing.recordCpu(testAgent, 1000L);

        var count = billing.usageCount(testAgent);

        assertEquals(3, count);
    }

    // endregion

    // region ========== totalTokens ==========

    @Test
    void testTotalTokens() {
        billing.recordToken(testAgent, 100);
        billing.recordToken(testAgent, 200);
        billing.recordToken(testAgent, 300);

        var total = billing.totalTokens(testAgent);

        assertEquals(600, total);
    }

    // endregion
}
