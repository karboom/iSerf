package me.karboom.java.iSerf.agent.billing;

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

    @BeforeEach
    void setUp() {
        recordedCosts = new ArrayList<>();
        testLedger = recordedCosts::add;
        billing = new AgentBilling("test-agent-001", testLedger);
    }

    // region ========== recordMemory ==========

    @Test
    void testRecordMemory() {
        billing.recordMemory(1024L);

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
        billing.recordCpu(500_000L);

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
        billing.recordToken(2500);

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
        billing.recordDisk(4096);

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
        billing.recordTraffic(8192);

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
        billing.recordMemory(512L);
        billing.recordToken(100);
        billing.recordCpu(1000L);

        assertEquals(3, recordedCosts.size());
    }

    // endregion
}
