package me.karboom.java.iSerf.kit.tool.mathematics;

import me.karboom.java.iSerf.agent.tool.CallResult;
import org.apache.commons.math3.util.FastMath;
import org.apache.commons.math3.util.Precision;

/**
 * 数学工具共享方法
 */
public class MathUtil {

    /**
     * 格式化计算结果，整数时不显示小数部分
     * @param result 计算结果
     * @return 格式化后的 CallResult
     */
    public static CallResult formatResult(Double result) {
        String value;
        if (Precision.equals(result, FastMath.floor(result), 1.0e-10)) {
            value = String.valueOf(result.longValue());
        } else {
            value = String.valueOf(result);
        }
        return CallResult.builder()
                .llm(value)
                .build();
    }
}