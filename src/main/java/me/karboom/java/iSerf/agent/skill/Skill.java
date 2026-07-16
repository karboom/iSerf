package me.karboom.java.iSerf.agent.skill;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.nio.file.Path;
import java.util.List;

/**
 * Skill 技能类
 * 对应 Claude Code 的 SKILL.md 规范
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Skill {
    /**
     * 技能名称，默认为目录名
     */
    public String name;

    /**
     * 技能描述，告诉 LLM 何时使用此技能
     */
    public String description;

    /**
     * SKILL.md 的 markdown 指令内容
     */
    public String prompt;

    /**
     * 允许使用的工具名称列表
     */
    public List<String> allowedTools;

    /**
     * 禁用的工具名称列表
     */
    public List<String> disallowedTools;

    /**
     * 是否禁止 LLM 自动调用，仅允许手动触发
     */
    public Boolean disableModelInvocation;

    /**
     * 执行上下文：inline（内联）或 fork（子agent）
     */
    public String context;

    /**
     * skill 目录路径，用于 ${SKILL_DIR} 替换
     */
    public Path dir;
}
