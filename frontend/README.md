# IELTS AI Writing Frontend

现有 IELTS 写作后端的第一版可视化页面。前端只调用已有 API，不保存数据库密码、DeepSeek API Key 或用户 ID，也不修改后端的 Session 认证方式。

## 页面功能

- 用户注册、图片验证码登录；刷新后通过 `GET /api/auth/me` 恢复当前登录状态。
- 左侧查看自己的作文列表，每页 10 篇，按后端活跃时间排序。
- 新建作文：可“提交并批改”，也可“仅保存”。提交批改后，前端轮询后台任务直到完成；批改接口自身会新建 Essay，前端不会先普通创建再批改。
- 查看作文题目、原文、词数、四项分数、后端计算的总分和反馈。
- 查看该作文的消息，发送后续提问；AI 失败时重新读取已保存的 USER 消息。
- 确认后删除整篇作文及其聊天消息。

## 本地启动

先按 [后端 README](../ielts-ai-backend/README.md) 启动 Spring Boot，默认在 `http://localhost:8080`。新数据库仍需要先补齐或准备后端 README 说明的基础表。

在另一个终端启动前端（需要 Node.js 和 pnpm）：

```powershell
cd .\ielts-ai-frontend
pnpm install
pnpm dev
```

打开终端输出的本地地址，通常为 `http://localhost:5173`。开发服务器把浏览器的 `/api` 请求代理到 `http://localhost:8080`，所以验证码、登录和后续请求可以使用同一浏览器 Session Cookie。请始终通过前端地址访问页面，不要把前端 API URL 改成直接跨端口请求后端。

本机若没有把 pnpm 加入 PATH，也可以使用已配置的包管理器运行相同的 `install`、`dev`、`build`、`test` 脚本。

```powershell
pnpm test
pnpm build
```

前端不会向浏览器暴露 `DEEPSEEK_API_KEY`；它仍由后端环境变量提供。当前后端没有登出接口，也没有流式 AI 回复接口，因此第一版页面不伪装实现这两项功能。

## 接口与错误处理

所有接口集中在 [src/api.js](src/api.js)，只使用相对路径 `/api/...` 和同源 Cookie。注册与普通保存 Essay 的成功响应是文本；其余主要接口是 JSON。HTTP 错误保留 `ApiErrorResponse` 的 `status`、`message` 及可选的 `essayId`、`userMessageId` 等字段。

提交批改时前端会发送 `Idempotency-Key`。`POST /api/essays/evaluate` 返回 `202` 和任务 ID 后，前端轮询 `GET /api/evaluation-tasks/{taskId}`；任务完成或失败后打开已保存的 Essay，暂时无法确认任务状态时也不会要求用户重复提交。聊天返回 502/504 时 USER 消息可能已经提交，前端用 `userMessageId` 提示并重新加载消息。再次手动提交同样的聊天内容会新建 USER 消息，不是对旧消息的幂等重试。
