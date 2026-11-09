export class ApiError extends Error {
  constructor(status, payload) {
    super(payload?.message || `请求失败（HTTP ${status}）`)
    this.name = 'ApiError'
    this.status = status
    this.payload = payload
  }
}

async function request(path, options = {}) {
  const { method = 'GET', body, headers, ...otherOptions } = options
  const response = await fetch(path, {
    ...otherOptions,
    method,
    credentials: 'same-origin',
    headers: body === undefined ? headers : { 'Content-Type': 'application/json', ...headers },
    body: body === undefined ? undefined : JSON.stringify(body),
  })

  const contentType = response.headers.get('content-type') || ''
  const payload = contentType.includes('application/json')
    ? await response.json()
    : await response.text()

  if (!response.ok) {
    throw new ApiError(response.status, payload)
  }
  return payload
}

export const api = {
  register: (body) => request('/api/users/register', { method: 'POST', body }),
  login: (body) => request('/api/auth/login', { method: 'POST', body }),
  me: () => request('/api/auth/me'),
  essays: (page = 1) => request(`/api/essays?page=${page}&pageSize=10`),
  essay: (id) => request(`/api/essays/${id}`),
  evaluate: (body, idempotencyKey) => request('/api/essays/evaluate', {
    method: 'POST',
    body,
    headers: { 'Idempotency-Key': idempotencyKey },
  }),
  evaluationTask: (id) => request(`/api/evaluation-tasks/${id}`),
  saveEssay: (body) => request('/api/essays', { method: 'POST', body }),
  deleteEssay: (id) => request(`/api/essays/${id}`, { method: 'DELETE' }),
  messages: (id) => request(`/api/essays/${id}/messages`),
  sendMessage: (id, body) => request(`/api/essays/${id}/messages`, { method: 'POST', body }),
}
