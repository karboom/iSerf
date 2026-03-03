# 行为习惯
* 写完代码不需要跟我解释，不需要总结
* Todo 的内容不用理会
* 函数的注释不要修改

* 明确要求重构的时候，先删除函数内容，再重新开始写


# 代码风格
* 类名在使用的时候，不要携带包名称前缀
* 循环尽可能用stream流式写法
* 当一个类的成员包含同名静态子类的时候，将静态子类的常量用作变量赋值，当变量对比的时候（静态常量右置）
* enum类型使用.name()获取对应的字符串
* 变量用 var 声明
* 不使用try-catch捕获异常，直接抛出。函数签名里面的throw用@SneakyThrows注解代替
* 字符串拼接统一采用.formatted方法，不能缺失%s
* 可以用函数接口的情况下，就不要用Class + override
* 多行文本使用Text Blocks（三引号）
* 基础数据类型使用对应的封装类
* 当判断条件为单个变量，使用switch语句
* 类名BO结尾（以及其静态子类），必须有@Data  @Builder  @NoArgsConstructor  @AllArgsConstructor 四个注解

# 数据处理
* 操作ObjectNode的时候使用 node.path()作为句柄，然后使用.isMissingNode 和 .isNull 判断是否为空

# 错误处理
* http出现错误的时候，要打印body

# 日志
* 类使用@Slf4j注入日志接口
* 关键变量需要用 log.debug 进行英文日志输出，格式：<函数名> + 日志内容


# 测试规范
* 写测试用例的时候，不需要mock
* 超时使用assertTimeoutPreemptively来实现
* Performance类型的测试不需要断言，只要输出结果即可
* 测试用例无需 @Slf4j