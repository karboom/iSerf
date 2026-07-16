package me.karboom.java.iSerf.agent.skill;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SkillLoader 测试类
 */
@Timeout(10)
public class SkillLoaderTest {

    private SkillLoader loader;

    @BeforeEach
    void setUp() {
        loader = new SkillLoader();
    }

    // region ========== fromPath ==========

    @Nested
    class FromPathTests {

        @Test
        void testLoadAllSkills() {
            var skillsDir = Path.of("src/test/resources/agent/skill");
            var skills = loader.fromPath(skillsDir);

            assertNotNull(skills, "应该返回非空列表");
            assertEquals(3, skills.size(), "应该找到3个skill");
        }

        @Test
        void testSkillNames() {
            var skillsDir = Path.of("src/test/resources/agent/skill");
            var skills = loader.fromPath(skillsDir);

            var names = skills.stream()
                    .map(Skill::getName)
                    .sorted()
                    .toList();

            assertTrue(names.contains("deploy"), "应该包含deploy skill");
            assertTrue(names.contains("summarize-changes"), "应该包含summarize-changes skill");
            assertTrue(names.contains("no-frontmatter"), "应该包含no-frontmatter skill");
        }

        @Test
        void testNullPath() {
            var skills = loader.fromPath(null);

            assertNotNull(skills, "应该返回非空列表");
            assertTrue(skills.isEmpty(), "null路径应该返回空列表");
        }

        @Test
        void testNonExistentPath() {
            var skills = loader.fromPath(Path.of("non/existent/path"));

            assertNotNull(skills, "应该返回非空列表");
            assertTrue(skills.isEmpty(), "不存在的路径应该返回空列表");
        }

        @Test
        void testEmptyDirectory() {
            var tempDir = Path.of(System.getProperty("java.io.tmpdir"), "empty-skills-" + System.currentTimeMillis());
            try {
                java.nio.file.Files.createDirectories(tempDir);
                var skills = loader.fromPath(tempDir);

                assertNotNull(skills, "应该返回非空列表");
                assertTrue(skills.isEmpty(), "空目录应该返回空列表");
            } catch (Exception e) {
                fail("创建临时目录失败: " + e.getMessage());
            } finally {
                try {
                    java.nio.file.Files.deleteIfExists(tempDir);
                } catch (Exception ignored) {}
            }
        }
    }

    // endregion

    // region ========== fromSkillDir ==========

    @Nested
    class FromSkillDirTests {

        @Test
        void testLoadSkillWithFullFrontmatter() {
            var skillDir = Path.of("src/test/resources/agent/skill/summarize-changes");
            var skill = loader.fromSkillDir(skillDir);

            assertNotNull(skill, "应该返回非null skill");
            assertEquals("summarize-changes", skill.getName());
            assertEquals("Summarizes uncommitted changes and flags anything risky. Use when the user asks what changed.", skill.getDescription());
            assertFalse(skill.getDisableModelInvocation(), "disableModelInvocation应该是false");
            assertEquals("inline", skill.getContext());

            // 验证 allowedTools
            assertNotNull(skill.getAllowedTools(), "allowedTools不应为null");
            assertEquals(3, skill.getAllowedTools().size(), "allowedTools应该有3个元素");
            assertTrue(skill.getAllowedTools().contains("Read"), "应该包含Read");
            assertTrue(skill.getAllowedTools().contains("Grep"), "应该包含Grep");
            assertTrue(skill.getAllowedTools().contains("Bash"), "应该包含Bash");

            // 验证 disallowedTools
            assertNotNull(skill.getDisallowedTools(), "disallowedTools不应为null");
            assertEquals(1, skill.getDisallowedTools().size(), "disallowedTools应该有1个元素");
            assertTrue(skill.getDisallowedTools().contains("Write"), "应该包含Write");

            // 验证 prompt
            assertNotNull(skill.getPrompt(), "prompt不应为null");
            assertTrue(skill.getPrompt().contains("## Current changes"), "prompt应该包含markdown内容");
            assertTrue(skill.getPrompt().contains("## Instructions"), "prompt应该包含指令部分");
        }

        @Test
        void testLoadSkillWithArrayTools() {
            var skillDir = Path.of("src/test/resources/agent/skill/deploy");
            var skill = loader.fromSkillDir(skillDir);

            assertNotNull(skill, "应该返回非null skill");
            assertEquals("deploy", skill.getName());
            assertEquals("Deploy the application to production", skill.getDescription());
            assertTrue(skill.getDisableModelInvocation(), "disableModelInvocation应该是true");
            assertEquals("fork", skill.getContext());

            // 验证 allowedTools (数组格式)
            assertNotNull(skill.getAllowedTools(), "allowedTools不应为null");
            assertEquals(3, skill.getAllowedTools().size(), "allowedTools应该有3个元素");
            assertTrue(skill.getAllowedTools().contains("Bash"), "应该包含Bash");
            assertTrue(skill.getAllowedTools().contains("Read"), "应该包含Read");
            assertTrue(skill.getAllowedTools().contains("Write"), "应该包含Write");
        }

        @Test
        void testLoadSkillWithoutFrontmatter() {
            var skillDir = Path.of("src/test/resources/agent/skill/no-frontmatter");
            var skill = loader.fromSkillDir(skillDir);

            assertNotNull(skill, "应该返回非null skill");
            assertEquals("no-frontmatter", skill.getName(), "名称应该是目录名");
            // description 应该从 prompt 第一段生成
            assertNotNull(skill.getDescription(), "description不应为null");
            assertNotNull(skill.getPrompt(), "prompt不应为null");
            assertTrue(skill.getPrompt().contains("## Simple Skill"), "prompt应该包含markdown内容");
        }

        @Test
        void testNullSkillDir() {
            var skill = loader.fromSkillDir(null);
            assertNull(skill, "null目录应该返回null");
        }

        @Test
        void testNonExistentSkillDir() {
            var skill = loader.fromSkillDir(Path.of("non/existent/skill"));
            assertNull(skill, "不存在的目录应该返回null");
        }

        @Test
        void testSkillDirWithoutSkillMd() {
            var tempDir = Path.of(System.getProperty("java.io.tmpdir"), "no-skill-md-" + System.currentTimeMillis());
            try {
                java.nio.file.Files.createDirectories(tempDir);
                var skill = loader.fromSkillDir(tempDir);
                assertNull(skill, "没有SKILL.md的目录应该返回null");
            } catch (Exception e) {
                fail("创建临时目录失败: " + e.getMessage());
            } finally {
                try {
                    java.nio.file.Files.deleteIfExists(tempDir);
                } catch (Exception ignored) {}
            }
        }

        @Test
        void testSkillDirProperty() {
            var skillDir = Path.of("src/test/resources/agent/skill/summarize-changes");
            var skill = loader.fromSkillDir(skillDir);

            assertNotNull(skill, "应该返回非null skill");
            assertEquals(skillDir, skill.getDir(), "dir属性应该等于传入的目录路径");
        }
    }

    // endregion

    // region ========== Edge Cases ==========

    @Nested
    class EdgeCaseTests {

        @Test
        void testEmptySkillMd() {
            var tempDir = Path.of(System.getProperty("java.io.tmpdir"), "empty-skill-" + System.currentTimeMillis());
            try {
                java.nio.file.Files.createDirectories(tempDir);
                var skillMdPath = tempDir.resolve("SKILL.md");
                java.nio.file.Files.writeString(skillMdPath, "");

                var skill = loader.fromSkillDir(tempDir);
                assertNull(skill, "空的SKILL.md应该返回null");
            } catch (Exception e) {
                fail("测试失败: " + e.getMessage());
            } finally {
                try {
                    java.nio.file.Files.deleteIfExists(tempDir.resolve("SKILL.md"));
                    java.nio.file.Files.deleteIfExists(tempDir);
                } catch (Exception ignored) {}
            }
        }

        @Test
        void testBlankSkillMd() {
            var tempDir = Path.of(System.getProperty("java.io.tmpdir"), "blank-skill-" + System.currentTimeMillis());
            try {
                java.nio.file.Files.createDirectories(tempDir);
                var skillMdPath = tempDir.resolve("SKILL.md");
                java.nio.file.Files.writeString(skillMdPath, "   \n\n   ");

                var skill = loader.fromSkillDir(tempDir);
                assertNull(skill, "空白内容的SKILL.md应该返回null");
            } catch (Exception e) {
                fail("测试失败: " + e.getMessage());
            } finally {
                try {
                    java.nio.file.Files.deleteIfExists(tempDir.resolve("SKILL.md"));
                    java.nio.file.Files.deleteIfExists(tempDir);
                } catch (Exception ignored) {}
            }
        }

        @Test
        void testMalformedFrontmatter() {
            var tempDir = Path.of(System.getProperty("java.io.tmpdir"), "malformed-skill-" + System.currentTimeMillis());
            try {
                java.nio.file.Files.createDirectories(tempDir);
                var skillMdPath = tempDir.resolve("SKILL.md");
                var content = """
                        ---
                        name: test-skill
                        invalid yaml: [unclosed
                        ---
                        
                        ## Instructions
                        
                        Some content here.
                        """;
                java.nio.file.Files.writeString(skillMdPath, content);

                var skill = loader.fromSkillDir(tempDir);
                // 即使 frontmatter 解析失败，skill 仍然应该被加载
                assertNotNull(skill, "malformed frontmatter 时 skill 仍应被加载");
                assertEquals("malformed-skill-" + tempDir.getFileName().toString().substring("malformed-skill-".length()), skill.getName());
            } catch (Exception e) {
                fail("测试失败: " + e.getMessage());
            } finally {
                try {
                    java.nio.file.Files.deleteIfExists(tempDir.resolve("SKILL.md"));
                    java.nio.file.Files.deleteIfExists(tempDir);
                } catch (Exception ignored) {}
            }
        }

        @Test
        void testCommaSeparatedTools() {
            var tempDir = Path.of(System.getProperty("java.io.tmpdir"), "comma-tools-" + System.currentTimeMillis());
            try {
                java.nio.file.Files.createDirectories(tempDir);
                var skillMdPath = tempDir.resolve("SKILL.md");
                var content = """
                        ---
                        name: comma-test
                        description: Test comma separated tools
                        allowed-tools: Read,Write,Bash
                        ---
                        
                        Instructions here.
                        """;
                java.nio.file.Files.writeString(skillMdPath, content);

                var skill = loader.fromSkillDir(tempDir);
                assertNotNull(skill, "应该返回非null skill");
                assertNotNull(skill.getAllowedTools(), "allowedTools不应为null");
                assertEquals(3, skill.getAllowedTools().size(), "应该解析出3个工具");
                assertTrue(skill.getAllowedTools().contains("Read"), "应该包含Read");
                assertTrue(skill.getAllowedTools().contains("Write"), "应该包含Write");
                assertTrue(skill.getAllowedTools().contains("Bash"), "应该包含Bash");
            } catch (Exception e) {
                fail("测试失败: " + e.getMessage());
            } finally {
                try {
                    java.nio.file.Files.deleteIfExists(tempDir.resolve("SKILL.md"));
                    java.nio.file.Files.deleteIfExists(tempDir);
                } catch (Exception ignored) {}
            }
        }
    }

    // endregion
}
