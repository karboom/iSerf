# 行为习惯
* Todo 的内容不用理会
* 函数的注释不要修改
* 明确要求重构的时候，先删除函数内容，再重新开始写
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
* 类名BO结尾（以及其静态子类），必须有@Data  @Builder  @NoArgsConstructor  @AllArgsConstructor 四个注解
* 通过异常优先退出的写法，降低代码层数
* 带有泛型的类，初始化需要加{}
* 对整个代码区域分块的注释，使用 region  endregion 标记
* 当泛型类初始化的时候，使用匿名类写法 new Class<Type> {}，然后set属性

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
* 关键变量需要用 log.debug 进行英文日志输出，格式：" <函数名> 日志内容 "


# 测试规范
* 写测试用例的时候，不需要mock
* 超时使用@Timeout来实现
* Performance类型的测试不需要断言，只要输出结果即可
* 一个函数只写一个用例
* 多组测试数据的情况，写一个json数组文件，然后将输出按照顺序输出新的json数组文件（-时间戳）命名
* 被测试对象的构造放在@BeforeEach里面