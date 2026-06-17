package me.karboom.java.iSerf.server.metaData;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MemoryMetaDataTest {

    private MemoryMetaData metaData;

    @BeforeEach
    void setUp() {
        metaData = new MemoryMetaData();
    }

    // region ========== Node Operations ==========

    @Nested
    class NodeOperations {

        @Test
        void testAddNode() {
            var node = Node.builder()
                    .id("node-1")
                    .ip("192.168.1.1")
                    .port(8080)
                    .build();

            metaData.addNode(node);

            var nodes = metaData.getNodes();
            assertEquals(1, nodes.size());
            assertEquals("node-1", nodes.get(0).getId());
            assertEquals("192.168.1.1", nodes.get(0).getIp());
            assertEquals(8080, nodes.get(0).getPort());
        }

        @Test
        void testAddMultipleNodes() {
            metaData.addNode(Node.builder().id("node-1").ip("192.168.1.1").port(8080).build());
            metaData.addNode(Node.builder().id("node-2").ip("192.168.1.2").port(8081).build());
            metaData.addNode(Node.builder().id("node-3").ip("192.168.1.3").port(8082).build());

            var nodes = metaData.getNodes();
            assertEquals(3, nodes.size());
        }

        @Test
        void testRemoveNode() {
            metaData.addNode(Node.builder().id("node-1").ip("192.168.1.1").port(8080).build());
            metaData.addNode(Node.builder().id("node-2").ip("192.168.1.2").port(8081).build());

            metaData.removeNode("node-1");

            var nodes = metaData.getNodes();
            assertEquals(1, nodes.size());
            assertEquals("node-2", nodes.get(0).getId());
        }

        @Test
        void testRemoveNonExistentNode() {
            metaData.addNode(Node.builder().id("node-1").ip("192.168.1.1").port(8080).build());

            assertDoesNotThrow(() -> metaData.removeNode("non-existent"));

            var nodes = metaData.getNodes();
            assertEquals(1, nodes.size());
        }

        @Test
        void testGetNodesEmpty() {
            var nodes = metaData.getNodes();
            assertTrue(nodes.isEmpty());
        }

        @Test
        void testGetNodesReturnsDefensiveCopy() {
            metaData.addNode(Node.builder().id("node-1").ip("192.168.1.1").port(8080).build());

            var nodes = metaData.getNodes();
            nodes.clear();

            assertEquals(1, metaData.getNodes().size(), "Modifying returned list should not affect internal state");
        }
    }

    // endregion

    // region ========== Agent Stay Operations ==========

    @Nested
    class AgentStayOperations {

        @Test
        void testSetAndGetAgentStay() {
            metaData.setAgentStay("agent-1", "node-1");

            var stayNode = metaData.getAgentStay("agent-1");
            assertEquals("node-1", stayNode);
        }

        @Test
        void testGetAgentStayNonExistent() {
            var stayNode = metaData.getAgentStay("non-existent");
            assertNull(stayNode);
        }

        @Test
        void testSetAgentStayOverwrite() {
            metaData.setAgentStay("agent-1", "node-1");
            metaData.setAgentStay("agent-1", "node-2");

            var stayNode = metaData.getAgentStay("agent-1");
            assertEquals("node-2", stayNode);
        }

        @Test
        void testMultipleAgentsStay() {
            metaData.setAgentStay("agent-1", "node-1");
            metaData.setAgentStay("agent-2", "node-2");
            metaData.setAgentStay("agent-3", "node-1");

            assertEquals("node-1", metaData.getAgentStay("agent-1"));
            assertEquals("node-2", metaData.getAgentStay("agent-2"));
            assertEquals("node-1", metaData.getAgentStay("agent-3"));
        }
    }

    // endregion

    // region ========== Team Stay Operations ==========

    @Nested
    class TeamStayOperations {

        @Test
        void testSetAndGetTeamStay() {
            metaData.setTeamStay("team-1", "node-1");

            var stayNode = metaData.getTeamStay("team-1");
            assertEquals("node-1", stayNode);
        }

        @Test
        void testGetTeamStayNonExistent() {
            var stayNode = metaData.getTeamStay("non-existent");
            assertNull(stayNode);
        }

        @Test
        void testSetTeamStayOverwrite() {
            metaData.setTeamStay("team-1", "node-1");
            metaData.setTeamStay("team-1", "node-2");

            var stayNode = metaData.getTeamStay("team-1");
            assertEquals("node-2", stayNode);
        }

        @Test
        void testRemoveTeamStay() {
            metaData.setTeamStay("team-1", "node-1");

            metaData.removeTeamStay("team-1");

            var stayNode = metaData.getTeamStay("team-1");
            assertNull(stayNode);
        }

        @Test
        void testRemoveTeamStayNonExistent() {
            assertDoesNotThrow(() -> metaData.removeTeamStay("non-existent"));
        }

        @Test
        void testMultipleTeamsStay() {
            metaData.setTeamStay("team-1", "node-1");
            metaData.setTeamStay("team-2", "node-2");
            metaData.setTeamStay("team-3", "node-1");

            assertEquals("node-1", metaData.getTeamStay("team-1"));
            assertEquals("node-2", metaData.getTeamStay("team-2"));
            assertEquals("node-1", metaData.getTeamStay("team-3"));
        }
    }

    // endregion

    // region ========== Agent Subscribe Nodes Operations ==========

    @Nested
    class AgentSubscribeNodesOperations {

        @Test
        void testAddAndGetAgentSubscribeNodes() {
            metaData.addAgentSubscribeNode("agent-1", "node-1");
            metaData.addAgentSubscribeNode("agent-1", "node-2");

            var nodes = metaData.getAgentSubscribeNodes("agent-1");
            assertEquals(2, nodes.size());
            assertTrue(nodes.contains("node-1"));
            assertTrue(nodes.contains("node-2"));
        }

        @Test
        void testGetAgentSubscribeNodesNonExistent() {
            var nodes = metaData.getAgentSubscribeNodes("non-existent");
            assertTrue(nodes.isEmpty());
        }

        @Test
        void testRemoveAgentSubscribeNode() {
            metaData.addAgentSubscribeNode("agent-1", "node-1");
            metaData.addAgentSubscribeNode("agent-1", "node-2");
            metaData.addAgentSubscribeNode("agent-1", "node-3");

            metaData.removeAgentSubscribeNode("agent-1", "node-2");

            var nodes = metaData.getAgentSubscribeNodes("agent-1");
            assertEquals(2, nodes.size());
            assertTrue(nodes.contains("node-1"));
            assertFalse(nodes.contains("node-2"));
            assertTrue(nodes.contains("node-3"));
        }

        @Test
        void testRemoveAgentSubscribeNodeNonExistent() {
            metaData.addAgentSubscribeNode("agent-1", "node-1");

            assertDoesNotThrow(() -> metaData.removeAgentSubscribeNode("agent-1", "non-existent"));

            var nodes = metaData.getAgentSubscribeNodes("agent-1");
            assertEquals(1, nodes.size());
        }

        @Test
        void testRemoveAgentSubscribeNodeFromNonExistentAgent() {
            assertDoesNotThrow(() -> metaData.removeAgentSubscribeNode("non-existent", "node-1"));
        }

        @Test
        void testMultipleAgentsSubscribeNodes() {
            metaData.addAgentSubscribeNode("agent-1", "node-1");
            metaData.addAgentSubscribeNode("agent-1", "node-2");
            metaData.addAgentSubscribeNode("agent-2", "node-2");
            metaData.addAgentSubscribeNode("agent-2", "node-3");

            var agent1Nodes = metaData.getAgentSubscribeNodes("agent-1");
            var agent2Nodes = metaData.getAgentSubscribeNodes("agent-2");

            assertEquals(2, agent1Nodes.size());
            assertEquals(2, agent2Nodes.size());
            assertTrue(agent1Nodes.contains("node-1"));
            assertTrue(agent1Nodes.contains("node-2"));
            assertTrue(agent2Nodes.contains("node-2"));
            assertTrue(agent2Nodes.contains("node-3"));
        }

        @Test
        void testAddDuplicateSubscribeNode() {
            metaData.addAgentSubscribeNode("agent-1", "node-1");
            metaData.addAgentSubscribeNode("agent-1", "node-1");

            var nodes = metaData.getAgentSubscribeNodes("agent-1");
            assertEquals(2, nodes.size(), "Duplicate nodes should be added (List behavior)");
        }
    }

    // endregion
}
