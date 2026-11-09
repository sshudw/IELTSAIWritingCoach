# API 错误响应

HTTP 400、401、404、409、429、500、502、504 使用 `ApiErrorResponse`，`status` 与 HTTP 状态一致。
所有错误都包含 `status`、`message`；可选字段为 `essayId`、`userMessageId`、
`evaluationStatus`、`aiModel`。可选字段为空时不输出，现有字段名称和层级保持一致。

```json
{
  "status": 401,
  "message": "Not logged in"
}
```

```json
{
  "status": 502,
  "message": "USER message saved, but AI reply failed",
  "userMessageId": 31
}
```

## 两条处理路径

- Controller 已知的业务错误：保留原来的判断与状态码，通过 `ApiErrorResponse` 创建响应。
- MVC 抛出的异常：`GlobalExceptionHandler` 继承 `ResponseEntityExceptionHandler`，
  使用 Spring 选定的状态码和响应头，统一替换为 JSON 错误体。

JSON 解析失败、缺少请求体、参数类型不匹配返回 400 `Invalid request`；
注册、登录、创建作文、提交批改、发送消息的请求体使用 `@Valid` 和 `@NotBlank`。
字段缺失、`null`、空字符串和纯空白在进入 Controller 方法前返回 400，例如：

```json
{
  "status": 400,
  "message": "content is required"
}
```

多个字段无效时按字段名排序合并提示，例如 `email is required; username is required`。
错误体不包含原始字段值。字段校验不会 trim 输入，不改变密码或作文内容。
受保护的 `/api/**` 接口会先由登录拦截器检查 Session；未登录时直接返回 401，不再进入请求体绑定和字段校验。登录后字段为空仍返回 400。公开的登录、注册和验证码接口不经过登录检查。
登录字段为空时不会进入 AuthService，也不会消费 Session 中的验证码。
本阶段只校验非空，不新增邮箱格式、密码强度规则。现有作文类型、长度和聊天 UTF-8 字节限制继续保留。

未知接口或静态资源返回 404 `Resource not found`。
405、415 等 MVC 错误也使用统一 JSON，并保留原有状态与 `Allow` 等响应头。
未捕获内部异常返回 500 `Internal server error`，详细异常记录在服务端日志中。
全局处理器显式设置 `application/json`，验证码图片接口发生内部异常时也返回 JSON。

同步聊天的 AI 失败结果仍由 Service 返回给 Controller，并保留已保存的 USER 消息 ID。异步批改的 AI 失败不是 HTTP 错误：`GET /api/evaluation-tasks/{taskId}` 返回 HTTP 200、`status: FAILED` 和安全的 `errorMessage`。同一用户用相同 `Idempotency-Key` 提交不同批改内容时返回 409。用户在一分钟固定窗口内超过配置的批改提交次数时返回 429 `Too many AI evaluation requests`。
不改变 Service 的事务、Session、AI 调用、分页和数据库行为，也不改变成功响应。
该处理器作用于 Spring MVC 异常处理流程；容器或 Filter 在 MVC 外发生的错误不属于它的处理范围。

## Apifox 检查

重启应用后：

1. 不携带登录 Cookie 请求 `GET /api/essays`，检查 HTTP 401 和 JSON 中的 `status: 401`。
2. 登录后请求 `GET /api/essays?page=0&pageSize=10`，检查 400 和原有分页提示。
3. 请求 `GET /api/essays?page=abc`，检查 MVC 返回 400 和 `Invalid request`。
4. 请求 `GET /api/no-such-endpoint`，检查 404 和 `Resource not found`。
5. 向消息接口发送不完整 JSON（例如单独一个 `{`），检查 400。
6. 用相同 `Idempotency-Key` 提交两份不同批改内容，检查 409 和统一错误体。
7. 在同一分钟内超过 `EVALUATION_REDIS_MAX_REQUESTS_PER_MINUTE`，检查 429 和统一错误体。

500、502、504 使用自动化测试模拟故障验证，无需为测试这些响应破坏数据库或 API 配置。

```powershell
.\mvnw.cmd test
```

`ApiErrorResponseTests` 使用实际 Spring Boot MVC 配置和模拟 Service，
验证登录拦截、公开路径、业务错误、MVC 错误、内部异常及 AI 失败响应，不调用真实 DeepSeek 或数据库。
