package me.karboom.java.iSerf.kit.tool;

import lombok.SneakyThrows;
import me.karboom.java.iSerf.tool.Tool;
import org.apache.commons.math3.util.FastMath;
import org.apache.commons.math3.util.Precision;

import java.util.List;

public class Mathematics {
    public Mathematics() {
    }

    /**
     * 加法运算工具
     * @return
     */
    public Tool add() {
        return Tool.builder()
                .name("add")
                .description("执行加法运算，将两个数字相加")
                .type(Tool.TYPE.FUNCTION)
                .parameters(List.of(
                        new Tool.Parameter("a", "number", "第一个数字", true),
                        new Tool.Parameter("b", "number", "第二个数字", true)
                ))
                .function((params) -> {
                    var a = Double.valueOf(params.get("a").toString());
                    var b = Double.valueOf(params.get("b").toString());
                    var result = a + b;
                    return formatResult(result);
                })
                .build();
    }

    /**
     * 减法运算工具
     * @return
     */
    public Tool subtract() {
        return Tool.builder()
                .name("subtract")
                .description("执行减法运算，将两个数字相减")
                .type(Tool.TYPE.FUNCTION)
                .parameters(List.of(
                        new Tool.Parameter("a", "number", "被减数", true),
                        new Tool.Parameter("b", "number", "减数", true)
                ))
                .function((params) -> {
                    var a = Double.valueOf(params.get("a").toString());
                    var b = Double.valueOf(params.get("b").toString());
                    var result = a - b;
                    return formatResult(result);
                })
                .build();
    }

    /**
     * 乘法运算工具
     * @return
     */
    public Tool multiply() {
        return Tool.builder()
                .name("multiply")
                .description("执行乘法运算，将两个数字相乘")
                .type(Tool.TYPE.FUNCTION)
                .parameters(List.of(
                        new Tool.Parameter("a", "number", "第一个数字", true),
                        new Tool.Parameter("b", "number", "第二个数字", true)
                ))
                .function((params) -> {
                    var a = Double.valueOf(params.get("a").toString());
                    var b = Double.valueOf(params.get("b").toString());
                    var result = a * b;
                    return formatResult(result);
                })
                .build();
    }

    /**
     * 除法运算工具
     * @return
     */
    @SneakyThrows
    public Tool divide() {
        return Tool.builder()
                .name("divide")
                .description("执行除法运算，将两个数字相除")
                .type(Tool.TYPE.FUNCTION)
                .parameters(List.of(
                        new Tool.Parameter("a", "number", "被除数", true),
                        new Tool.Parameter("b", "number", "除数", true)
                ))
                .function((params) -> {
                    var a = Double.valueOf(params.get("a").toString());
                    var b = Double.valueOf(params.get("b").toString());
                    if (b == 0) {
                        throw new ArithmeticException("除数不能为零");
                    }
                    var result = a / b;
                    return formatResult(result);
                })
                .build();
    }

    /**
     * 幂运算工具
     * @return
     */
    public Tool power() {
        return Tool.builder()
                .name("power")
                .description("执行幂运算，计算a的b次方")
                .type(Tool.TYPE.FUNCTION)
                .parameters(List.of(
                        new Tool.Parameter("base", "number", "底数", true),
                        new Tool.Parameter("exponent", "number", "指数", true)
                ))
                .function((params) -> {
                    var base = Double.valueOf(params.get("base").toString());
                    var exponent = Double.valueOf(params.get("exponent").toString());
                    var result = FastMath.pow(base, exponent);
                    return formatResult(result);
                })
                .build();
    }

    /**
     * 求模运算工具
     * @return
     */
    @SneakyThrows
    public Tool modulo() {
        return Tool.builder()
                .name("modulo")
                .description("执行求模运算，计算a对b取余")
                .type(Tool.TYPE.FUNCTION)
                .parameters(List.of(
                        new Tool.Parameter("a", "number", "被除数", true),
                        new Tool.Parameter("b", "number", "除数", true)
                ))
                .function((params) -> {
                    var a = Double.valueOf(params.get("a").toString());
                    var b = Double.valueOf(params.get("b").toString());
                    if (b == 0) {
                        throw new ArithmeticException("除数不能为零");
                    }
                    var result = a % b;
                    return formatResult(result);
                })
                .build();
    }

    /**
     * 开方运算工具
     * @return
     */
    @SneakyThrows
    public Tool sqrt() {
        return Tool.builder()
                .name("sqrt")
                .description("执行开方运算，计算数字的平方根")
                .type(Tool.TYPE.FUNCTION)
                .parameters(List.of(
                        new Tool.Parameter("number", "number", "要求平方根的数字", true)
                ))
                .function((params) -> {
                    var num = Double.valueOf(params.get("number").toString());
                    if (num < 0) {
                        throw new ArithmeticException("不能对负数开平方根");
                    }
                    var result = FastMath.sqrt(num);
                    return formatResult(result);
                })
                .build();
    }

    /**
     * 绝对值运算工具
     * @return
     */
    public Tool abs() {
        return Tool.builder()
                .name("abs")
                .description("计算数字的绝对值")
                .type(Tool.TYPE.FUNCTION)
                .parameters(List.of(
                        new Tool.Parameter("number", "number", "要求绝对值的数字", true)
                ))
                .function((params) -> {
                    var num = Double.valueOf(params.get("number").toString());
                    var result = FastMath.abs(num);
                    return formatResult(result);
                })
                .build();
    }

    /**
     * 对数运算工具
     * @return
     */
    @SneakyThrows
    public Tool log() {
        return Tool.builder()
                .name("log")
                .description("计算数字的自然对数(以e为底)")
                .type(Tool.TYPE.FUNCTION)
                .parameters(List.of(
                        new Tool.Parameter("number", "number", "要求自然对数的数字", true)
                ))
                .function((params) -> {
                    var num = Double.valueOf(params.get("number").toString());
                    if (num <= 0) {
                        throw new ArithmeticException("数字必须大于零");
                    }
                    var result = FastMath.log(num);
                    return formatResult(result);
                })
                .build();
    }

    /**
     * 常用对数运算工具
     * @return
     */
    @SneakyThrows
    public Tool log10() {
        return Tool.builder()
                .name("log10")
                .description("计算数字的常用对数(以10为底)")
                .type(Tool.TYPE.FUNCTION)
                .parameters(List.of(
                        new Tool.Parameter("number", "number", "要求常用对数的数字", true)
                ))
                .function((params) -> {
                    var num = Double.valueOf(params.get("number").toString());
                    if (num <= 0) {
                        throw new ArithmeticException("数字必须大于零");
                    }
                    var result = FastMath.log10(num);
                    return formatResult(result);
                })
                .build();
    }

    /**
     * 正弦运算工具
     * @return
     */
    public Tool sin() {
        return Tool.builder()
                .name("sin")
                .description("计算数字的正弦值(弧度制)")
                .type(Tool.TYPE.FUNCTION)
                .parameters(List.of(
                        new Tool.Parameter("number", "number", "弧度值", true)
                ))
                .function((params) -> {
                    var num = Double.valueOf(params.get("number").toString());
                    var result = FastMath.sin(num);
                    return formatResult(result);
                })
                .build();
    }

    /**
     * 余弦运算工具
     * @return
     */
    public Tool cos() {
        return Tool.builder()
                .name("cos")
                .description("计算数字的余弦值(弧度制)")
                .type(Tool.TYPE.FUNCTION)
                .parameters(List.of(
                        new Tool.Parameter("number", "number", "弧度值", true)
                ))
                .function((params) -> {
                    var num = Double.valueOf(params.get("number").toString());
                    var result = FastMath.cos(num);
                    return formatResult(result);
                })
                .build();
    }

    /**
     * 正切运算工具
     * @return
     */
    public Tool tan() {
        return Tool.builder()
                .name("tan")
                .description("计算数字的正切值(弧度制)")
                .type(Tool.TYPE.FUNCTION)
                .parameters(List.of(
                        new Tool.Parameter("number", "number", "弧度值", true)
                ))
                .function((params) -> {
                    var num = Double.valueOf(params.get("number").toString());
                    var result = FastMath.tan(num);
                    return formatResult(result);
                })
                .build();
    }

    /**
     * 反正弦运算工具
     * @return
     */
    public Tool asin() {
        return Tool.builder()
                .name("asin")
                .description("计算数字的反正弦值(返回弧度)")
                .type(Tool.TYPE.FUNCTION)
                .parameters(List.of(
                        new Tool.Parameter("number", "number", "正弦值(-1到1之间)", true)
                ))
                .function((params) -> {
                    var num = Double.valueOf(params.get("number").toString());
                    var result = FastMath.asin(num);
                    return formatResult(result);
                })
                .build();
    }

    /**
     * 反余弦运算工具
     * @return
     */
    public Tool acos() {
        return Tool.builder()
                .name("acos")
                .description("计算数字的反余弦值(返回弧度)")
                .type(Tool.TYPE.FUNCTION)
                .parameters(List.of(
                        new Tool.Parameter("number", "number", "余弦值(-1到1之间)", true)
                ))
                .function((params) -> {
                    var num = Double.valueOf(params.get("number").toString());
                    var result = FastMath.acos(num);
                    return formatResult(result);
                })
                .build();
    }

    /**
     * 反正切运算工具
     * @return
     */
    public Tool atan() {
        return Tool.builder()
                .name("atan")
                .description("计算数字的反正切值(返回弧度)")
                .type(Tool.TYPE.FUNCTION)
                .parameters(List.of(
                        new Tool.Parameter("number", "number", "正切值", true)
                ))
                .function((params) -> {
                    var num = Double.valueOf(params.get("number").toString());
                    var result = FastMath.atan(num);
                    return formatResult(result);
                })
                .build();
    }

    /**
     * 向上取整运算工具
     * @return
     */
    public Tool ceil() {
        return Tool.builder()
                .name("ceil")
                .description("计算数字的向上取整值")
                .type(Tool.TYPE.FUNCTION)
                .parameters(List.of(
                        new Tool.Parameter("number", "number", "要求向上取整的数字", true)
                ))
                .function((params) -> {
                    var num = Double.valueOf(params.get("number").toString());
                    var result = FastMath.ceil(num);
                    return String.valueOf((long) result);
                })
                .build();
    }

    /**
     * 向下取整运算工具
     * @return
     */
    public Tool floor() {
        return Tool.builder()
                .name("floor")
                .description("计算数字的向下取整值")
                .type(Tool.TYPE.FUNCTION)
                .parameters(List.of(
                        new Tool.Parameter("number", "number", "要求向下取整的数字", true)
                ))
                .function((params) -> {
                    var num = Double.valueOf(params.get("number").toString());
                    var result = FastMath.floor(num);
                    return String.valueOf((long) result);
                })
                .build();
    }

    /**
     * 四舍五入运算工具
     * @return
     */
    public Tool round() {
        return Tool.builder()
                .name("round")
                .description("计算数字的四舍五入值")
                .type(Tool.TYPE.FUNCTION)
                .parameters(List.of(
                        new Tool.Parameter("number", "number", "要求四舍五入的数字", true)
                ))
                .function((params) -> {
                    var num = Double.valueOf(params.get("number").toString());
                    var result = FastMath.round(num);
                    return String.valueOf(result);
                })
                .build();
    }

    /**
     * 最大值运算工具
     * @return
     */
    public Tool max() {
        return Tool.builder()
                .name("max")
                .description("计算两个数字中的最大值")
                .type(Tool.TYPE.FUNCTION)
                .parameters(List.of(
                        new Tool.Parameter("a", "number", "第一个数字", true),
                        new Tool.Parameter("b", "number", "第二个数字", true)
                ))
                .function((params) -> {
                    var a = Double.valueOf(params.get("a").toString());
                    var b = Double.valueOf(params.get("b").toString());
                    var result = FastMath.max(a, b);
                    return formatResult(result);
                })
                .build();
    }

    /**
     * 最小值运算工具
     * @return
     */
    public Tool min() {
        return Tool.builder()
                .name("min")
                .description("计算两个数字中的最小值")
                .type(Tool.TYPE.FUNCTION)
                .parameters(List.of(
                        new Tool.Parameter("a", "number", "第一个数字", true),
                        new Tool.Parameter("b", "number", "第二个数字", true)
                ))
                .function((params) -> {
                    var a = Double.valueOf(params.get("a").toString());
                    var b = Double.valueOf(params.get("b").toString());
                    var result = FastMath.min(a, b);
                    return formatResult(result);
                })
                .build();
    }

    /**
     * 指数运算工具
     * @return
     */
    public Tool exp() {
        return Tool.builder()
                .name("exp")
                .description("计算e的n次方(指数函数)")
                .type(Tool.TYPE.FUNCTION)
                .parameters(List.of(
                        new Tool.Parameter("number", "number", "指数n", true)
                ))
                .function((params) -> {
                    var num = Double.valueOf(params.get("number").toString());
                    var result = FastMath.exp(num);
                    return formatResult(result);
                })
                .build();
    }

    /**
     * 格式化结果
     * @param result 计算结果
     * @return 格式化后的字符串
     */
    private String formatResult(Double result) {
        if (Precision.equals(result, FastMath.floor(result), 1.0e-10)) {
            return String.valueOf(result.longValue());
        }
        return String.valueOf(result);
    }

    public List<Tool> all() {
        return List.of(
                add(), subtract(), multiply(), divide(), power(), modulo(), sqrt(), abs(),
                log(), log10(), sin(), cos(), tan(), asin(), acos(), atan(),
                ceil(), floor(), round(), max(), min(), exp()
        );
    }
}
