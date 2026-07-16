package me.karboom.java.iSerf.agent.skill;

import lombok.extern.slf4j.Slf4j;
import me.karboom.java.iSerf.util.YAMLUtil;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Skill 加载器
 * 从文件系统加载 SKILL.md 文件并解析为 Skill 对象
 */
@Slf4j
public class SkillLoader {

    /**
     * 从目录加载所有 skill
     * 扫描 skillsDir 下的一级子目录，每个子目录视为一个 skill
     *
     * @param skillsDir skill 根目录
     * @return skill 列表
     */
    public List<Skill> fromPath(Path skillsDir) {
        var skills = new ArrayList<Skill>();

        if (skillsDir == null || !Files.exists(skillsDir) || !Files.isDirectory(skillsDir)) {
            return skills;
        }

        try (Stream<Path> stream = Files.list(skillsDir)) {
            var skillDirs = stream.filter(Files::isDirectory).toList();

            for (var skillDir : skillDirs) {
                var skill = fromSkillDir(skillDir);
                if (skill != null) {
                    skills.add(skill);
                }
            }
        } catch (Exception e) {
            log.error("<fromPath> failed to load skills from {} | {}", skillsDir, e.getMessage());
        }

        log.debug("<fromPath> loaded skills | count={},dir={}", skills.size(), skillsDir);
        return skills;
    }

    /**
     * 从单个 skill 目录加载 SKILL.md
     *
     * @param skillDir skill 目录
     * @return 解析后的 Skill 对象，解析失败返回 null
     */
    public Skill fromSkillDir(Path skillDir) {
        if (skillDir == null || !Files.exists(skillDir) || !Files.isDirectory(skillDir)) {
            return null;
        }

        var skillMdPath = skillDir.resolve("SKILL.md");
        if (!Files.exists(skillMdPath)) {
            log.debug("<fromSkillDir> SKILL.md not found | dir={}", skillDir);
            return null;
        }

        try {
            var content = Files.readString(skillMdPath, StandardCharsets.UTF_8);
            return parseSkillMd(content, skillDir);
        } catch (Exception e) {
            log.error("<fromSkillDir> failed to parse SKILL.md | dir={},error={}", skillDir, e.getMessage());
            return null;
        }
    }

    /**
     * 解析 SKILL.md 内容
     * 格式：
     * ---
     * name: my-skill
     * description: 技能描述
     * allowed-tools: Read Grep Bash
     * ---
     * 
     * ## 指令内容
     * 具体的操作步骤...
     *
     * @param content SKILL.md 文件内容
     * @param skillDir skill 目录路径
     * @return 解析后的 Skill 对象
     */
    private Skill parseSkillMd(String content, Path skillDir) {
        if (content == null || content.isBlank()) {
            return null;
        }

        var builder = Skill.builder();
        builder.dir(skillDir);

        // 默认名称为目录名
        var defaultName = skillDir.getFileName().toString();
        builder.name(defaultName);

        // 解析 frontmatter
        var frontmatterEnd = -1;
        if (content.startsWith("---")) {
            var secondDelimiter = content.indexOf("---", 3);
            if (secondDelimiter > 0) {
                var frontmatter = content.substring(3, secondDelimiter).trim();
                frontmatterEnd = secondDelimiter + 3;

                // 解析 YAML frontmatter
                try {
                    var yamlNode = YAMLUtil.parse(frontmatter.getBytes(StandardCharsets.UTF_8));

                    // name
                    var nameNode = yamlNode.path("name");
                    if (!nameNode.isMissingNode() && !nameNode.isNull()) {
                        builder.name(nameNode.asText());
                    }

                    // description
                    var descNode = yamlNode.path("description");
                    if (!descNode.isMissingNode() && !descNode.isNull()) {
                        builder.description(descNode.asText());
                    }

                    // allowed-tools (可能是字符串或数组)
                    var allowedToolsNode = yamlNode.path("allowed-tools");
                    if (!allowedToolsNode.isMissingNode() && !allowedToolsNode.isNull()) {
                        builder.allowedTools(parseToolList(allowedToolsNode));
                    }

                    // disallowed-tools
                    var disallowedToolsNode = yamlNode.path("disallowed-tools");
                    if (!disallowedToolsNode.isMissingNode() && !disallowedToolsNode.isNull()) {
                        builder.disallowedTools(parseToolList(disallowedToolsNode));
                    }

                    // disable-model-invocation
                    var disableNode = yamlNode.path("disable-model-invocation");
                    if (!disableNode.isMissingNode() && !disableNode.isNull()) {
                        builder.disableModelInvocation(disableNode.asBoolean(false));
                    }

                    // context
                    var contextNode = yamlNode.path("context");
                    if (!contextNode.isMissingNode() && !contextNode.isNull()) {
                        builder.context(contextNode.asText("inline"));
                    }

                } catch (Exception e) {
                    log.warn("<parseSkillMd> failed to parse frontmatter | error={}", e.getMessage());
                }
            }
        }

        // 解析 markdown body 作为 prompt
        var prompt = frontmatterEnd > 0 
                ? content.substring(frontmatterEnd).trim() 
                : content.trim();
        builder.prompt(prompt);

        // 如果没有 description，使用 prompt 的第一段作为 description
        if (builder.build().getDescription() == null && !prompt.isBlank()) {
            var firstParagraph = prompt.split("\n\n")[0].trim();
            if (firstParagraph.length() > 200) {
                firstParagraph = firstParagraph.substring(0, 200) + "...";
            }
            builder.description(firstParagraph);
        }

        return builder.build();
    }

    /**
     * 解析工具列表
     * 支持空格分隔的字符串或 YAML 数组
     *
     * @param node YAML 节点
     * @return 工具名称列表
     */
    private List<String> parseToolList(tools.jackson.databind.JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }

        if (node.isArray()) {
            var list = new ArrayList<String>();
            for (var element : node) {
                list.add(element.asText());
            }
            return list;
        }

        // 字符串形式，按空格或逗号分隔
        var text = node.asText();
        if (text == null || text.isBlank()) {
            return null;
        }

        return Arrays.stream(text.split("[\\s,]+"))
                .filter(s -> !s.isBlank())
                .collect(Collectors.toList());
    }
}
