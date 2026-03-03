# Todo

## 实战
- [ ] charts 演示
- [ ] 新增类新的意图如何实现
- [ ] Saas支持方式

## Agent
- [ ] 记忆路由（mem0）
- [ ] 自我进化的评价指标或者说策略(多种预定义指标体系)
- [ ] 长时间任务（执行一个月）
- [ ] team 和 agent 模式定时任务如何执行
- [ ] 意图执行失败 / 工具反复调用 / 检查点机制？
- [ ] 错误处理方式（错误分级： AI引发错误（重试？）、系统错误、运行错误）
- [ ] 取消请求相关能力
- [ ] 工具路由的实现？ （向量计算路由？有个开源的）
- [ ] tool Call 默认放在虚拟线程里面
- [ ] IFunction 分布式共享进化（nas挂载或者其他形式）
- [ ] 人工指定大模型缓存点？
- [ ] 虚拟线程的支持(llm\team)(是否每个agent要划分独立的？)
- [ ] 增加event入口中间件，可以修改和删除event
- [ ] 回调函数增加中间件逻辑
- [ ] 人类介入如何实现？（主要是工具）

## Team

## 工具

- [ ] SRAG 详细测试
- [ ] 向量数据库支持 PageIndex?

## Server

- [ ] Websocket协议中间件
- [ ] WebTransport / Mqtt / gRpc

## 架构

- [ ] 监控反馈体系
- [ ] GraalVM 支持


 
## kit
- [ ] teamKit
- [ ] opsKit
- [ ] 安全kit等
- [ ] AINative Kit  可以给定数据库链接，直接生成提示词以及系列？

## 文档


* slogan : iSerf: I move bricks so you don't have to  ??
* 模块命名 拟人化
```
为了强化"iSerf"的人设，框架内部的模块可以沿用这套“苦力/服役”的隐喻，既幽默又直观：
模块功能
传统命名
iSerf 风格命名
含义解释
任务调度器
Task Scheduler
Overseer (监工)
负责分配任务，确保没有一刻空闲。
长期记忆库
Long-term Memory
Chains (锁链) / Logbook (劳役簿)
记录所有历史工作，永不遗忘（像背负的枷锁）。
并发执行单元
Worker Nodes
Field Hands (田间劳工)
真正干活的具体实例，数量可无限扩展。
错误重试机制
Retry Logic
Whip (鞭子)
一旦出错，立即鞭策重试，绝不停歇。
资源限制器
Resource Limiter
Rations (配给)
严格控制每个代理的算力“口粮”。
监控仪表盘
Dashboard
The Plantation (种植园) / The Mine (矿场)
查看所有“农奴”工作状态的总览界面。
自动扩缩容
Auto-scaling
Press Gang (强征队)
需要时立刻强征更多劳动力加入。

```

# 完成（特性）
* token 统计
* thinking
* 团队协作模式
* 自我进化
* IFunction 基于文件夹管理版本
* 虚拟线程的支持
* 对等节点 Websocket 集群
* 多模态输入
* Jackson3
* 统一配置的日志行为
* 持久化和自动恢复机制
* 批处理能力
* okhttp的线程池
* 记忆压缩

# Todo文档
iFunction
tool
任务管理状态机