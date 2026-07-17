# 行为习惯
* Todo 的内容不用理会
* 现有逻辑冲突的时候，不改Interface，改代码实现
* 修改函数签名以后，要检查引用同步修改


# 代码风格
* 变量用 var 声明
* 循环尽可能用stream流式写法
* 类名在使用的时候，不要携带包名称前缀
* 当一个类的成员包含同名静态子类的时候，将静态子类的常量用作变量赋值，当变量对比的时候（静态常量右置）
* enum类型使用.name()获取对应的字符串
* 字符串拼接统一采用.formatted方法，使用%s而不是{}
* 多行文本使用Text Blocks（三引号）
* 可以用函数接口的情况下，就不要用Class + override
* 基础数据类型使用对应的封装类
* 当判断条件为单个变量，使用switch语句
* BO（以及其静态子类），必须有@Data  @Builder  @NoArgsConstructor  @AllArgsConstructor 四个注解
* 通过异常优先退出的写法，降低代码层数
* 对整个代码区域分块的注释，使用 region  endregion 标记
* 当泛型类初始化的时候，使用匿名类写法 new Class<Type> {}，然后set属性
* @Override 要换行

# 并发处理
* 禁用 synchronized

# 数据处理
* 操作ObjectNode的时候使用 node.path()作为句柄，然后使用.isMissingNode 和 .isNull 判断是否为空
* JSON操作使用JSONUtil的静态方法处理
* id性质的变量使用DataUtil生成

# 错误处理
* http出现错误的时候，要打印body
* 不使用try-catch捕获异常，直接抛出。函数签名里面的throw用@SneakyThrows注解代替
* 使用throw ErrorUtil.make() 抛出错误

# 日志
* 类使用@Slf4j注入日志接口
* 函数入参、第三方函数调用的传参和结果、条件分支变量，都需要用log.debug进行英文日志输出，日志格式为："<{函数名}> 英文行为描述 | {变量}=?,{变量}=?...",变量为对象的时候，使用JSONUtil.stringify
* 当捕获的异常的时候，使用log.error进行英文日志输出，日志格式为："<{函数名}> {错误信息} | {错误堆栈}"。使用ExceptionUtil.stacktraceToString(e)


# 测试规范
* 写测试用例的时候，不需要mock
* 超时使用@Timeout来实现
* Performance类型的测试不需要断言，只要输出结果即可
* 多组测试数据的情况，写一个json数组文件，然后将输出按照顺序输出新的json数组文件（-时间戳）命名
* 被测试对象的构造放在@BeforeEach里面
* 同函数的测试用例，使用@Nest组织在一起
* 测试必须覆盖异常的情况
* 测试用例需要针对函数的入参以及入参的可能性，用正交法进行设计。正交表的内容写在测试用例的最开始。