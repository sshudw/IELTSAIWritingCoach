# Essay 多轮聊天

`POST /api/essays/{essayId}/messages` 同步保存提问、调用 DeepSeek、保存回答。
请求只接收 `{"content":"请解释第二段的问题"}`，用户 ID 来自登录 Session 的 `AUTH_USER_ID`。
所有权检查使用 `EssayMapper.findByIdAndUserId(essayId, currentUserId)`；未通过时既不保存消息也不调用 AI。

## 上下文

每次调用最多 10 条消息：一条 `system` 背景、最多 8 条历史消息、本次 `user` 提问。
背景包含作文类型、原题、原文、`overall_score`、`task_score`、`coherence_score`、
`lexical_score`、`grammar_score` 和数据库现有的初始 `ai_feedback`。
聊天不会覆盖 Essay 中的评分或 feedback，也不会把背景插入 `essay_message`。

背景中的原题、原文、feedback 分别最多保留 2,000、12,000、6,000 个 Unicode 字符，
超出时截取前部并标记 `[内容已截取]`（标记计入上限）。只截取发送给 AI 的副本，数据库原文保持完整。
缺失的分数标记为“暂无”，缺失的反馈标记为“暂无初始评分反馈”。

先保存本次 USER，取得数据库生成的 ID，然后执行：

```sql
SELECT id, essay_id AS essayId, `role`, content, created_at AS createdAt
FROM essay_message
WHERE essay_id = #{essayId} AND id < #{beforeId}
ORDER BY id DESC
LIMIT 8
```

组装时把历史按 ID 正序排列，再把最新 USER 追加一次；上下文不会混入另一篇作文。
库中角色为 `USER` / `ASSISTANT`，发给 DeepSeek 时转换为 `user` / `assistant`，也兼容既有的小写角色。

## 为什么 AI 失败不会回滚 USER

`EssayMessageService.createUserMessage` 使用 `NOT_SUPPORTED`，暂停可能存在的调用方事务。
它调用另一个 Spring Bean `EssayMessagePersistenceService.saveUserMessage`，该方法使用 `REQUIRES_NEW`。

```text
校验 Session 与所有权
  → 独立事务 INSERT USER → COMMIT
  → 查询历史并同步调用 DeepSeek（不在数据库事务中）
  → AI 成功：另一个独立事务 INSERT ASSISTANT → COMMIT
  → AI 失败：返回错误，USER 已经提交
```

保存 USER 的代理调用正常返回后才开始 AI 调用；如果提交本身失败，不会调用 AI。
分成两个 Spring Bean 是为了让事务代理生效，避免同一个类内自调用绕过代理。
保存 ASSISTANT 失败也不会影响已提交的 USER。
这保证的是后续失败不会回滚已经成功提交的消息；数据库本身无法写入时不能声称保存成功。

## 响应

成功返回 HTTP 201：

```json
{
  "userMessage": {
    "id": 31,
    "essayId": 21,
    "role": "USER",
    "content": "请解释第二段的问题",
    "createdAt": "2026-09-10T16:00:00"
  },
  "assistantMessage": {
    "id": 32,
    "essayId": 21,
    "role": "ASSISTANT",
    "content": "第二段可以进一步说明……",
    "createdAt": "2026-09-10T16:00:03"
  },
  "aiModel": "deepseek-v4-flash"
}
```

AI 失败返回 HTTP 502，超时返回 504：

```json
{
  "userMessageId": 31,
  "message": "USER message saved, but AI reply failed"
}
```

USER 保存之后发生历史查询或 ASSISTANT 保存错误时返回 500，响应同样包含 `userMessageId`。
未登录返回 401；Essay 不存在或不属于当前用户返回 404。
空消息或超过 60,000 UTF-8 字节的消息返回 400，避免超过现有 MySQL TEXT 字段容量。
AI 回复必须是非空且完整的文本，并通过长度检查后才保存。

## Apifox 验证

1. 让 Spring Boot 进程读取已有的 `DEEPSEEK_API_KEY` 环境变量并重启应用。
2. 按现有验证码和登录流程登录，保留 `JSESSIONID` Cookie。
3. 用 `GET /api/essays?page=1&pageSize=10` 找到自己的作文 ID。
4. 发送 `POST http://localhost:8080/api/essays/21/messages`，Body 选择 JSON，只填 `content`。
5. 收到 201 后，用 `GET /api/essays/21/messages` 确认 USER 和 ASSISTANT 都存在。
6. 继续提问，AI 会收到固定背景和选定的历史消息。
7. 若收到包含 `userMessageId` 的 502/504，再 GET 消息列表，应能看到对应的 USER。

同样的提问再次 POST 会创建新的 USER 记录，之前失败的提问仍然保留；当前没有专门的重试或去重接口。
每条记录是独立消息，当前同步接口尚未实现并发提问的排队功能。

## 聊天框分页与最近使用排序

聊天框列表接口为：

```http
GET /api/essays?page=1&pageSize=10
```

`page` 从 1 开始，`pageSize` 当前固定为 10。只查询登录 Session 中
`AUTH_USER_ID` 对应的 Essay；客户端传入其他 `userId` 不参与查询。
分页偏移量为 `(page - 1) * 10`，总页数按 `(total + 9) / 10` 向上取整。

响应示例：

```json
{
  "page": 1,
  "pageSize": 10,
  "total": 23,
  "totalPages": 3,
  "essays": [
    {
      "id": 8,
      "title": "Some people think public transport should be free. Discuss.",
      "essayType": "TASK_2",
      "wordCount": 268,
      "overallScore": 6.5,
      "evaluationStatus": "COMPLETED",
      "lastActivityAt": "2026-09-14T15:30:00"
    }
  ]
}
```

`title` 直接取 Essay 的 `question`，不增加重复的标题字段。查询按
`last_activity_at DESC, id DESC` 排序；用户发送一条新的 USER 消息时，
该 Essay 的 `last_activity_at` 和 USER 消息在同一个 `REQUIRES_NEW` 事务中更新并提交，
所以旧聊天会回到第一页最前面。若 AI 随后调用失败，已经提交的 USER 消息和活动时间不会回滚；
若 USER 消息保存失败，则活动时间也会一起回滚。

首次启用前执行一次 `database/add_essay_last_activity.sql`。它会补齐已有 Essay 的活动时间，
并创建 `(user_id, last_activity_at DESC, id DESC)` 联合索引。该迁移脚本只执行一次。

## 删除整个聊天框

一篇 Essay 就是一个独立聊天框。删除接口为：

```http
DELETE /api/essays/{essayId}
```

用户 ID 仍然来自登录 Session，SQL 同时使用 Essay ID 与当前用户 ID：

```sql
DELETE FROM essay WHERE id = #{id} AND user_id = #{userId}
```

删除成功后，MySQL 通过 `essay_message.essay_id -> essay.id` 外键的
`ON DELETE CASCADE` 自动删除该聊天框下面的全部消息。接口不提供删除单条消息的能力。
成功返回 200 和 `{"message":"Essay conversation deleted successfully"}`；
Essay 不存在或属于其他用户时统一返回 404，未登录返回 401。

## 测试

常规测试不调用真实 DeepSeek：

```powershell
.\mvnw.cmd test
```

启用真实 MySQL 的提交边界验证（使用当前 datasource 配置）：

```powershell
.\mvnw.cmd test "-Dessay.chat.mysql-test=true"
```

该测试创建自己专用的临时用户和作文，验证跨连接可见性、外层回滚不影响 USER，以及历史 SQL 边界。
测试结束后只清理自己创建的记录；AI 使用模拟响应，不消耗真实 API 额度。

## 代码入口

- `controller/EssayMessageController.java`：Session、参数检查、HTTP 响应。
- `service/EssayMessageService.java`：所有权检查、保存与调用的先后顺序、失败处理。
- `service/EssayMessagePersistenceService.java`：USER 和 ASSISTANT 各自的独立事务。
- `service/EssayChatContextBuilder.java`：背景截取、全部分数、历史排序、最多 10 条。
- `service/AiChatService.java`：读取现有 AI 配置，发送 HTTP JSON，解析普通聊天回复。
- `mapper/EssayMessageMapper.java`：按本次 USER ID 查之前的最近 8 条消息。

聊天继续复用现有表、环境变量和依赖；登录、Session 以及聊天 Prompt/解析保持不变。初始评分现已改为 README 所述的异步任务流程，不影响本页的同步聊天接口。
