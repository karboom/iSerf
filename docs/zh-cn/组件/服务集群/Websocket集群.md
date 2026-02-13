# Websocket 集群
提供一个弹性伸缩的集群服务，每个节点是对等的，即提供服务，也充当路由。扩缩容无需迁移节点内容。外层可以增加负载均衡来统一入口。

![Websocket集群架构](https://karboom-blog.oss-cn-hangzhou.aliyuncs.com/iSlogger/%E6%9E%B6%E6%9E%84%E5%9B%BE-%E6%9C%8D%E5%8A%A1%E9%9B%86%E7%BE%A4.webp)

```java

```

评测

负载均衡配置

- K8S

负载均衡使用headless service服务域名，通过dns解析获取nodes

- ECS

直接将ip池所有地址写入Nginx，直接将ip池数组写入nodes