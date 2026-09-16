# Spring Cloud Gateway 微服务网关示例

基于 **JDK 21 + Spring Boot 4.0.x + Spring Cloud 2025.1.x** 的多模块 Maven 工程，
包含一个微服务网关和两个用于验证连通性的示例服务。网关内置 **基于 Redis 令牌桶的多维限流**
（IP 白名单、全局限流、接口限流、用户限流）和 **统一全局异常处理**；Redis 使用纯 Java 的
内嵌实现（jedis-mock）随应用自动启停，**无需安装任何外部中间件即可直接运行**。

---

## 一、技术栈

| 分类 | 技术 / 组件 | 版本 | 说明 |
|---|---|---|---|
| 运行环境 | JDK | 21（Temurin 验证通过） | Spring Boot 4 要求 17+ |
| 基础框架 | Spring Boot | 4.0.8 | 由 Spring Cloud BOM 统一对齐 |
| 微服务框架 | Spring Cloud | 2025.1.3 | |
| 网关 | Spring Cloud Gateway（WebFlux） | 5.0.3 | starter：`spring-cloud-starter-gateway-server-webflux` |
| 限流存储 | Redis（Lettuce 响应式客户端） | 随 Boot BOM | 令牌桶状态存储 + Lua 原子脚本 |
| 内嵌 Redis | jedis-mock | 1.1.19 | 纯 Java Redis 实现，支持 EVAL/Lua，随应用启停 |
| 健康检查 | Spring Boot Actuator | 随 Boot BOM | `/actuator/health` |
| 构建工具 | Maven | 3.9+ | 多模块聚合工程 |

> 版本对齐关系：Spring Cloud `2025.1.3` → Spring Boot `4.0.8` → Spring Cloud Gateway `5.0.3`。
> 不要混用其他大版本，否则启动会报依赖兼容错误。

### Boot 4 / Spring Framework 7 的关键变化（本工程已适配）

- Spring Cloud Gateway 5 的路由配置前缀变为
  **`spring.cloud.gateway.server.webflux.routes`**（旧版为 `spring.cloud.gateway.routes`）；
- JSON 层升级为 **Jackson 3**，包名由 `com.fasterxml.jackson` 变为 `tools.jackson.*`；
- `ErrorWebExceptionHandler` 迁移到 `org.springframework.boot.webflux.error`；
- `EnvironmentPostProcessor` 迁移到顶层包 `org.springframework.boot`，且通过
  `META-INF/spring.factories` 注册（内嵌 Redis 的启动时机依赖于此）。

---

## 二、项目结构

```
spring-cloud-gateway-demo/          （父工程，统一管理依赖版本）
├── pom.xml
├── gateway-server/                 微服务网关（端口 8080）
│   └── src/main/
│       ├── java/com/example/gateway/
│       │   ├── GatewayApplication.java          启动类
│       │   ├── config/
│       │   │   ├── EmbeddedRedisEnvironmentPostProcessor.java
│       │   │   ├── EmbeddedRedisHolder.java     内嵌 Redis 生命周期（环境准备阶段启动）
│       │   │   ├── EmbeddedRedisConfig.java     应用关闭时停止内嵌 Redis
│       │   │   ├── GatewayConfig.java           限流属性 & ReactiveRedisTemplate
│       │   │   └── WebExceptionConfig.java
│       │   ├── ratelimit/
│       │   │   ├── RateLimitProperties.java     限流配置（app.rate-limit.*）
│       │   │   ├── RateLimitResult.java
│       │   │   ├── RateLimitExceededException.java
│       │   │   ├── IpCidrMatcher.java           IP/CIDR 白名单匹配
│       │   │   ├── TokenBucketService.java      Redis Lua 令牌桶
│       │   │   └── RateLimitGlobalFilter.java   多维限流全局过滤器
│       │   └── exception/
│       │       ├── ErrorResponse.java           统一错误响应体
│       │       ├── GlobalExceptionHandler.java  网关全局异常处理（最高优先级）
│       │       └── JsonWriter.java
│       └── resources/
│           ├── application.yml                  路由 + 限流配置
│           └── META-INF/spring/...EnvironmentPostProcessor.imports
├── demo-service-a/                 验证服务 A（端口 8081，Servlet MVC）
│   └── src/main/java/com/example/demoa/
│       ├── DemoServiceAApplication.java
│       └── controller/
│           ├── HealthController.java            GET /api/ping
│           └── GlobalExceptionHandler.java
├── demo-service-b/                 验证服务 B（端口 8082）
│   └── src/main/java/com/example/demob/
│       ├── DemoServiceBApplication.java
│       └── controller/
│           ├── HealthController.java            GET /api/ping
│           └── GlobalExceptionHandler.java
└── README.md
```

---

## 三、快速开始

### 前置条件

- JDK 21（`java -version` 为 21.x）
- Maven 3.9+（或使用 IDE 自带 Maven）
- **不需要安装 Redis**，网关启动时会自动拉起内嵌 Redis（默认随机空闲端口）

### 1. 编译打包

在项目根目录执行：

```bash
mvn clean package
```

### 2. 启动（需要三个终端，或后台运行）

```bash
# 终端 1：服务 A
java -jar demo-service-a/target/demo-service-a-1.0.0.jar

# 终端 2：服务 B
java -jar demo-service-b/target/demo-service-b-1.0.0.jar

# 终端 3：网关
java -jar gateway-server/target/gateway-server-1.0.0.jar
```

开发阶段也可以直接在 IDE 中运行三个 `*Application` 主类。

### 3. 验证路由转发

服务直连：

```bash
curl http://localhost:8081/api/ping    # 服务 A
curl http://localhost:8082/api/ping    # 服务 B
```

通过网关访问（`/service-a/**` → 服务 A、`/service-b/**` → 服务 B，`StripPrefix=1` 去掉第一段前缀）：

```bash
curl http://localhost:8080/service-a/api/ping
# {"service":"demo-service-a","message":"pong from service-a","timestamp":"..."}

curl http://localhost:8080/service-b/api/ping
# {"service":"demo-service-b","message":"pong from service-b","timestamp":"..."}
```

健康检查：

```bash
curl http://localhost:8080/actuator/health     # 网关
curl http://localhost:8081/actuator/health     # 服务 A
curl http://localhost:8082/actuator/health     # 服务 B
```

---

## 四、限流功能说明

### 4.1 处理流程

每个进入网关的请求，在路由匹配之后、转发到后端之前，依次经过
`RateLimitGlobalFilter`（order = 10100）：

```
请求 → ① IP 白名单命中？ ──是──► 直接放行（跳过所有限流）
        │否
        ▼
      ② 全局限流（所有请求共享一个桶） ──拒绝──► 429
        │通过
        ▼
      ③ 接口限流（按 routeId + 路径，如 rl:api:demo-service-a:/service-a/api/ping）
        │拒绝──► 429
        ▼
      ④ 用户限流（按请求头 X-User-Id；未携带该头则跳过） ──拒绝──► 429
        │通过
        ▼
      转发到后端服务
```

### 4.2 令牌桶算法

- 桶按 `capacity`（容量）初始化令牌，请求通过即扣 1 个令牌；
- 按 `refill-tokens / refill-seconds` 的速率持续补充（允许应对突发流量）；
- 补充与扣减在 **Redis Lua 脚本中原子完成**，并发下计数准确；
- 每个桶在 Redis 中以 `<key>:tokens`（剩余令牌）和 `<key>:ts`（上次填充毫秒时间戳）
  两个 key 保存，空闲 `expire-seconds` 后自动过期。

默认参数（便于直接观察限流效果，生产请调大）：

| 维度 | 容量 | 补充速率 | Redis key |
|---|---|---|---|
| 全局 | 20 | 10 / 秒 | `rl:global` |
| 接口 | 10 | 5 / 秒 | `rl:api:{routeId}:{path}` |
| 用户 | 5 | 2 / 秒 | `rl:user:{X-User-Id}` |

### 4.3 验证限流

```bash
# 接口限流：连续请求 12 次，前 10 次 200，之后 429
for i in $(seq 1 12); do
  curl -s -o /dev/null -w "$i: %{http_code}\n" http://localhost:8080/service-a/api/ping
done

# 用户限流：同一用户连续 6 次，第 6 次起 429
for i in $(seq 1 6); do
  curl -s -o /dev/null -w "$i: %{http_code}\n" \
    -H "X-User-Id: u1001" http://localhost:8080/service-a/api/ping
done

# 不同用户各自独立计数
curl -H "X-User-Id: u1002" http://localhost:8080/service-a/api/ping
```

被限流时返回统一 JSON（HTTP 429），并携带标准限流响应头：

```http
HTTP/1.1 429 TOO_MANY_REQUESTS
X-RateLimit-Limit: 10
X-RateLimit-Remaining: 0
Retry-After: 1
```

```json
{
  "timestamp": "2026-09-15T12:00:00Z",
  "status": 429,
  "error": "Too Many Requests",
  "code": "RATE_LIMITED",
  "message": "请求过于频繁，请稍后再试（维度: api）",
  "path": "/service-a/api/ping"
}
```

### 4.4 IP 白名单

在 `application.yml` 配置单 IP 或 CIDR，命中的客户端完全跳过限流：

```yaml
app:
  rate-limit:
    ip-whitelist:
      - 127.0.0.1
      - 10.0.0.0/8
      - 192.168.1.0/24
```

也可不改配置文件，启动时覆盖：

```bash
java -jar gateway-server/target/gateway-server-1.0.0.jar \
  --app.rate-limit.ip-whitelist=127.0.0.1
```

客户端 IP 默认取 TCP 对端地址；若网关部署在受信反向代理（Nginx/SLB）之后，
设置 `app.rate-limit.trust-forward-header=true` 后会优先解析 `X-Forwarded-For`
首个地址（不信任客户端直连时不要开启，以防伪造 IP 绕过限流）。

### 4.5 调整 / 关闭限流

```yaml
app:
  rate-limit:
    enabled: false            # 总开关，关闭后所有请求不做限流
    global: { capacity: 100, refill-tokens: 50, refill-seconds: 1 }
    api:    { capacity: 50,  refill-tokens: 20, refill-seconds: 1 }
    user:   { capacity: 20,  refill-tokens: 10, refill-seconds: 1 }
```

将某个维度（`global` / `api` / `user`）整段删除或注释，即关闭该维度。
也可通过命令行动态覆盖，例如：

```bash
java -jar gateway-server/target/gateway-server-1.0.0.jar \
  --app.rate-limit.api.capacity=100
```

---

## 五、内嵌 Redis 说明

- 网关通过 `EnvironmentPostProcessor` 在 Spring 容器启动前拉起 jedis-mock，
  并把实际监听地址（默认 `127.0.0.1` + 随机端口）写入 `spring.data.redis.host/port`，
  Lettuce 客户端自动连接，应用停止时自动关闭，**零外部依赖**。该阶段早于日志系统初始化，
  确认方式以 `/actuator/health` 的 `redis` 组件状态为准。
- 想固定端口（如用 redis-cli 观察 key）：`--app.embedded-redis.port=6379`。
- 想接入真实/外部 Redis（生产环境推荐，保证多网关节点共享限流状态）：

  ```bash
  java -jar gateway-server/target/gateway-server-1.0.0.jar \
    --spring.profiles.active=external \
    --spring.data.redis.host=10.0.0.1 \
    --spring.data.redis.port=6379
  ```

  `external` profile 下不会启动内嵌 Redis。

---

## 六、统一全局异常处理

- **网关**（WebFlux）：`GlobalExceptionHandler` 以最高优先级（`@Order(-2)`，
  早于框架默认错误处理器）拦截全部异常，统一输出 JSON：
  限流 → 429、路由不存在等 → 对应状态码（如 404）、参数错误 → 400、
  方法不支持 → 405、未预期异常 → 500。
- **后端服务**（MVC）：各自的 `@RestControllerAdvice GlobalExceptionHandler`
  统一封装 404/405/400/500 响应，结构与网关一致。

统一错误体格式：

```json
{
  "timestamp": "2026-09-15T12:00:00Z",
  "status": 404,
  "error": "Not Found",
  "code": "NOT_FOUND",
  "message": "...",
  "path": "/xxx"
}
```

---

## 七、常见问题

1. **启动报 `4.0.8` / `2025.1.3` 找不到？**
   请检查 Maven `settings.xml` 的镜像/仓库是否能访问 Maven Central；本工程不依赖私有仓库。
2. **改了路由前缀后 404？**
   路由配置在 `gateway-server/src/main/resources/application.yml`，
   `Path=/service-a/**` 与 `StripPrefix=1` 配合后，转发给后端的路径是去掉 `/service-a` 的部分。
3. **压测下限流计数不准？**
   单机内嵌/单 Redis 由 Lua 保证原子性；多网关节点必须连接同一个外部 Redis，
   各节点使用内嵌 Redis 时桶不共享（仅适合演示/单机）。
4. **如何确认内嵌 Redis 已启动？**
   网关启动后访问 `/actuator/health`，`redis` 组件状态为 `UP` 即表示已连接到内嵌 Redis
   （内嵌 Redis 在环境准备阶段启动，早于日志系统初始化，因此控制台不一定有启动日志）。

---

## 八、已验证的测试结果

以下均在本机（JDK 21）实测通过：

| 测试项 | 方法 | 结果 |
|---|---|---|
| 路由转发 | `curl localhost:8080/service-a/api/ping`、`/service-b/api/ping` | 200，分别返回两个服务的响应 |
| 健康检查 | 三个服务 `/actuator/health` | 网关 `redis` 组件 UP，整体 UP |
| 接口限流 | 接口桶容量 10，15 个并发请求 | 精确 10×200 + 5×429，`维度: api` |
| 用户限流 | 同一 `X-User-Id` 桶容量 5，7 并发 | 精确 5×200 + 2×429，`维度: user:xxx` |
| 用户隔离 | 两个不同用户同时压测 | 各自独立计数，互不影响 |
| 全局限流 | 仅开全局桶容量 8，15 个完全并发请求 | 精确 8×200 + 7×429，`维度: global` |
| IP 白名单（单 IP） | 配置 `127.0.0.1`，20 次突发（携带用户头） | 20×200，完全跳过限流 |
| IP 白名单（CIDR） | 配置 `127.0.0.0/8`，20 次突发 | 20×200 |
| 总开关 | `--app.rate-limit.enabled=false`，40 突发 | 40×200 |
| 令牌补充 | 被限流后等待 1~2 秒再请求 | 自动恢复 200 |
| 全局异常-404 | 访问不存在的网关路径 | 统一 JSON，HTTP 404 |
| 全局异常-405 | 对后端 GET 接口发 POST | 统一 JSON，HTTP 405 |
| 429 响应头 | 被限流时检查响应头 | 含 `X-RateLimit-Limit/Remaining`、`Retry-After` |
| external profile | 指定外部不存在的 Redis | 网关正常启动，限流 fail-open 放行并记录错误日志 |
| 干净构建 | `mvn clean package` | 三模块全部 BUILD SUCCESS，无警告 |
