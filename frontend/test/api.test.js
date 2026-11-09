import assert from 'node:assert/strict'
import { afterEach, test } from 'node:test'
import { api, ApiError } from '../src/api.js'

const originalFetch = globalThis.fetch

afterEach(() => {
  globalThis.fetch = originalFetch
})

test('注册沿用后端的纯文本成功响应', async () => {
  let request
  globalThis.fetch = async (url, options) => {
    request = { url, options }
    return new Response('User registered successfully', {
      status: 200,
      headers: { 'content-type': 'text/plain' },
    })
  }

  const result = await api.register({ username: 'alice', email: 'a@example.com', password: 'secret' })
  assert.equal(result, 'User registered successfully')
  assert.equal(request.url, '/api/users/register')
  assert.equal(request.options.credentials, 'same-origin')
  assert.deepEqual(JSON.parse(request.options.body), {
    username: 'alice', email: 'a@example.com', password: 'secret',
  })
})

test('作文分页始终请求后端固定的 pageSize=10', async () => {
  let url
  globalThis.fetch = async (path) => {
    url = path
    return Response.json({ page: 2, pageSize: 10, total: 12, totalPages: 2, essays: [] })
  }

  const result = await api.essays(2)
  assert.equal(url, '/api/essays?page=2&pageSize=10')
  assert.equal(result.totalPages, 2)
})

test('提交批改时发送幂等键并保留异步任务信息', async () => {
  let request
  globalThis.fetch = async (url, options) => {
    request = { url, options }
    return Response.json({ taskId: 12, essayId: 31, status: 'PENDING' }, { status: 202 })
  }

  const body = { question: 'Q', content: 'Essay', essayType: 'TASK_2' }
  const result = await api.evaluate(body, 'evaluation-key')
  assert.deepEqual(result, { taskId: 12, essayId: 31, status: 'PENDING' })
  assert.equal(request.url, '/api/essays/evaluate')
  assert.equal(request.options.headers['Content-Type'], 'application/json')
  assert.equal(request.options.headers['Idempotency-Key'], 'evaluation-key')
  assert.deepEqual(JSON.parse(request.options.body), body)
})

test('按任务 ID 查询异步批改状态', async () => {
  let url
  globalThis.fetch = async (path) => {
    url = path
    return Response.json({ taskId: 12, essayId: 31, status: 'COMPLETED', result: { essayId: 31 } })
  }

  const result = await api.evaluationTask(12)
  assert.equal(url, '/api/evaluation-tasks/12')
  assert.equal(result.status, 'COMPLETED')
})

test('聊天失败时保留已提交 USER 的消息 ID', async () => {
  let url
  globalThis.fetch = async (path) => {
    url = path
    return Response.json({
      status: 504, message: 'USER message saved, but AI reply timed out', userMessageId: 101,
    }, { status: 504 })
  }

  await assert.rejects(api.sendMessage(31, { content: 'Why?' }),
    (error) => error instanceof ApiError && error.status === 504 && error.payload.userMessageId === 101)
  assert.equal(url, '/api/essays/31/messages')
})
