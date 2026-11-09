<script setup>
import { computed, nextTick, onMounted, ref } from 'vue'
import { api, ApiError } from './api'

const currentUser = ref(null)
const booting = ref(true)
const bootError = ref('')

const authMode = ref('login')
const authForm = ref({ username: '', email: '', password: '', captcha: '' })
const authBusy = ref(false)
const authError = ref('')
const authNotice = ref('')
const captchaSrc = ref('')
const captchaFailed = ref(false)

const essays = ref([])
const page = ref(1)
const total = ref(0)
const totalPages = ref(0)
const listLoading = ref(false)
const listError = ref('')

const viewMode = ref('empty')
const selectedEssayId = ref(null)
const selectedEssay = ref(null)
const detailLoading = ref(false)
const detailError = ref('')
const messages = ref([])
const messagesLoading = ref(false)
const messagesError = ref('')
let selectionRequest = 0

const editor = ref({ question: '', content: '', essayType: 'TASK_2' })
const evaluationBusy = ref(false)
const saveBusy = ref(false)
const editorError = ref('')
const editorNotice = ref('')
const EVALUATION_POLL_INTERVAL_MS = 1000
const EVALUATION_POLL_ATTEMPTS = 120
let evaluationRequestKey = ''
let evaluationRequestFingerprint = ''

const composerText = ref('')
const chatBusy = ref(false)
const pendingMessage = ref('')
const chatError = ref('')
const chatNotice = ref('')
const conversationElement = ref(null)
const mainPanelElement = ref(null)

const deleteOpen = ref(false)
const deleteBusy = ref(false)
const deleteError = ref('')

const estimatedWords = computed(() => {
  const text = editor.value.content.trim()
  return text ? text.split(/\s+/).length : 0
})

const canSendMessage = computed(() =>
  Boolean(composerText.value.trim()) && !chatBusy.value && selectedEssayId.value !== null,
)

function refreshCaptcha() {
  authForm.value.captcha = ''
  captchaFailed.value = false
  captchaSrc.value = `/api/auth/captcha?refresh=${Date.now()}`
}

function readableError(error, fallback = '请求失败，请稍后再试。') {
  if (error instanceof ApiError) {
    if (error.status === 502 && !error.payload?.message) {
      return '无法连接后端，请确认 Spring Boot 已启动。'
    }
    return error.message
  }
  if (error instanceof TypeError) return '无法连接后端，请确认 Spring Boot 已启动。'
  return fallback
}

function resetToLogin(message = '登录已失效，请重新登录。') {
  selectionRequest += 1
  currentUser.value = null
  selectedEssayId.value = null
  selectedEssay.value = null
  essays.value = []
  messages.value = []
  viewMode.value = 'empty'
  authMode.value = 'login'
  authForm.value.password = ''
  authError.value = ''
  authNotice.value = message
  refreshCaptcha()
}

function handleUnauthorized(error) {
  if (error instanceof ApiError && error.status === 401) {
    resetToLogin()
    return true
  }
  return false
}

async function bootstrap() {
  try {
    currentUser.value = await api.me()
    await loadEssays(1, true)
  } catch (error) {
    if (!(error instanceof ApiError && error.status === 401)) {
      bootError.value = readableError(error, '暂时无法确认登录状态。')
    }
  } finally {
    booting.value = false
    if (!currentUser.value) refreshCaptcha()
  }
}

function switchAuth(mode) {
  if (authBusy.value) return
  authMode.value = mode
  authForm.value.password = ''
  authError.value = ''
  authNotice.value = ''
  if (mode === 'login') refreshCaptcha()
}

async function submitRegister() {
  authError.value = ''
  authNotice.value = ''
  if (!authForm.value.username.trim() || !authForm.value.email.trim() || !authForm.value.password.trim()) {
    authError.value = '请填写用户名、邮箱和密码。'
    return
  }

  authBusy.value = true
  try {
    await api.register({
      username: authForm.value.username,
      email: authForm.value.email,
      password: authForm.value.password,
    })
    authForm.value.password = ''
    authMode.value = 'login'
    authNotice.value = '注册成功，请使用刚创建的账号登录。'
    refreshCaptcha()
  } catch (error) {
    authError.value = readableError(error)
  } finally {
    authBusy.value = false
  }
}

async function submitLogin() {
  authError.value = ''
  authNotice.value = ''
  if (!authForm.value.username.trim() || !authForm.value.password.trim() || !authForm.value.captcha.trim()) {
    authError.value = '请填写账号、密码和图片验证码。'
    return
  }

  authBusy.value = true
  try {
    await api.login({
      username: authForm.value.username,
      password: authForm.value.password,
      captcha: authForm.value.captcha,
    })
    authForm.value.password = ''
    currentUser.value = await api.me()
    bootError.value = ''
    await loadEssays(1, true)
  } catch (error) {
    authError.value = readableError(error)
    refreshCaptcha()
  } finally {
    authBusy.value = false
  }
}

async function loadEssays(requestedPage = 1, selectFirst = false) {
  listLoading.value = true
  listError.value = ''
  try {
    const result = await api.essays(requestedPage)
    page.value = result.page
    total.value = result.total
    totalPages.value = result.totalPages
    essays.value = result.essays || []
    if (selectFirst && essays.value.length > 0) {
      await openEssay(essays.value[0].id)
    } else if (selectFirst && essays.value.length === 0) {
      selectedEssayId.value = null
      selectedEssay.value = null
      viewMode.value = 'empty'
    }
  } catch (error) {
    if (!handleUnauthorized(error)) listError.value = readableError(error)
  } finally {
    listLoading.value = false
  }
}

async function openEssay(id) {
  if (chatBusy.value) return
  const requestId = ++selectionRequest
  selectedEssayId.value = id
  selectedEssay.value = null
  messages.value = []
  viewMode.value = 'detail'
  detailLoading.value = true
  messagesLoading.value = true
  detailError.value = ''
  messagesError.value = ''
  chatError.value = ''
  chatNotice.value = ''
  composerText.value = ''

  const [essayResult, messageResult] = await Promise.allSettled([api.essay(id), api.messages(id)])
  if (requestId !== selectionRequest) return

  if (essayResult.status === 'fulfilled') {
    selectedEssay.value = essayResult.value
  } else {
    if (handleUnauthorized(essayResult.reason)) return
    detailError.value = readableError(essayResult.reason)
  }

  if (messageResult.status === 'fulfilled') {
    messages.value = messageResult.value
  } else if (!handleUnauthorized(messageResult.reason)) {
    messagesError.value = readableError(messageResult.reason)
  }
  detailLoading.value = false
  messagesLoading.value = false
  await scrollToLatest()
}

async function refreshMessages(id = selectedEssayId.value) {
  if (id === null) return false
  messagesLoading.value = true
  messagesError.value = ''
  try {
    const result = await api.messages(id)
    if (selectedEssayId.value === id) messages.value = result
    await scrollToLatest()
    return true
  } catch (error) {
    if (!handleUnauthorized(error)) messagesError.value = readableError(error)
    return false
  } finally {
    messagesLoading.value = false
  }
}

async function scrollToLatest() {
  await nextTick()
  if (conversationElement.value) {
    conversationElement.value.scrollTop = conversationElement.value.scrollHeight
  }
}

async function revealMainPanel() {
  await nextTick()
  if (window.matchMedia('(max-width: 640px)').matches) {
    const reducedMotion = window.matchMedia('(prefers-reduced-motion: reduce)').matches
    mainPanelElement.value?.scrollIntoView({ behavior: reducedMotion ? 'auto' : 'smooth', block: 'start' })
  }
}

function startEssay() {
  if (chatBusy.value) return
  selectionRequest += 1
  evaluationRequestKey = ''
  evaluationRequestFingerprint = ''
  selectedEssayId.value = null
  selectedEssay.value = null
  viewMode.value = 'editor'
  editor.value = { question: '', content: '', essayType: 'TASK_2' }
  editorError.value = ''
  editorNotice.value = ''
  revealMainPanel()
}

function validateEssayInput() {
  if (!editor.value.question.trim() || !editor.value.content.trim()) {
    editorError.value = '请填写作文题目和正文。'
    return false
  }
  return true
}

function createIdempotencyKey() {
  if (globalThis.crypto?.randomUUID) return globalThis.crypto.randomUUID()
  return `evaluation-${Date.now()}-${Math.random().toString(36).slice(2)}`
}

async function pollEvaluationTask(initialTask) {
  let task = initialTask
  for (let attempt = 0;
    attempt < EVALUATION_POLL_ATTEMPTS && !['COMPLETED', 'FAILED'].includes(task.status);
    attempt += 1) {
    await new Promise((resolve) => setTimeout(resolve, EVALUATION_POLL_INTERVAL_MS))
    task = await api.evaluationTask(task.taskId)
  }
  return task
}

async function submitEvaluation() {
  if (evaluationBusy.value || saveBusy.value) return
  editorError.value = ''
  editorNotice.value = ''
  if (!validateEssayInput()) return

  const requestBody = { ...editor.value }
  const requestFingerprint = JSON.stringify(requestBody)
  if (!evaluationRequestKey || evaluationRequestFingerprint !== requestFingerprint) {
    evaluationRequestKey = createIdempotencyKey()
    evaluationRequestFingerprint = requestFingerprint
  }

  let submittedTask = null
  evaluationBusy.value = true
  try {
    submittedTask = await api.evaluate(requestBody, evaluationRequestKey)
    evaluationRequestKey = ''
    evaluationRequestFingerprint = ''
    await loadEssays(1)
    if (!currentUser.value) return

    const task = await pollEvaluationTask(submittedTask)
    await loadEssays(1)
    if (currentUser.value) await openEssay(task.essayId ?? submittedTask.essayId)

    if (task.status === 'FAILED') {
      chatNotice.value = task.errorMessage
        ? `作文已保存，但这次 AI 批改没有成功：${task.errorMessage}`
        : '作文已保存，但这次 AI 批改没有成功。'
    } else if (task.status !== 'COMPLETED') {
      chatNotice.value = '作文已保存，AI 仍在后台批改。稍后重新打开这篇作文即可查看结果。'
    }
  } catch (error) {
    if (handleUnauthorized(error)) return
    const savedEssayId = submittedTask?.essayId
      ?? (error instanceof ApiError ? error.payload?.essayId : null)
    if (savedEssayId) {
      await loadEssays(1)
      if (currentUser.value) await openEssay(savedEssayId)
      chatNotice.value = submittedTask
        ? '作文已保存，但暂时无法确认 AI 批改进度。稍后重新打开这篇作文即可查看结果。'
        : '作文已保存，但这次 AI 批改没有成功。'
    } else {
      editorError.value = readableError(error)
    }
  } finally {
    evaluationBusy.value = false
  }
}

async function saveWithoutEvaluation() {
  editorError.value = ''
  editorNotice.value = ''
  if (!validateEssayInput()) return

  saveBusy.value = true
  try {
    await api.saveEssay({ ...editor.value })
    await loadEssays(1, true)
  } catch (error) {
    if (!handleUnauthorized(error)) editorError.value = readableError(error)
  } finally {
    saveBusy.value = false
  }
}

async function sendMessage() {
  const content = composerText.value.trim()
  const essayId = selectedEssayId.value
  if (!content || chatBusy.value || essayId === null) return

  chatError.value = ''
  chatNotice.value = ''
  chatBusy.value = true
  pendingMessage.value = content
  await scrollToLatest()
  try {
    const result = await api.sendMessage(essayId, { content })
    composerText.value = ''
    if (selectedEssayId.value === essayId) {
      messages.value = [...messages.value, result.userMessage, result.assistantMessage]
    }
    await refreshMessages(essayId)
    await loadEssays(1)
  } catch (error) {
    if (handleUnauthorized(error)) return
    if (error instanceof ApiError && error.payload?.userMessageId) {
      composerText.value = ''
      await refreshMessages(essayId)
      await loadEssays(1)
      chatError.value = `提问已保存（消息 #${error.payload.userMessageId}），但 AI 暂时没有完成回复。请查看聊天记录，避免重复提交。`
    } else {
      chatError.value = readableError(error)
    }
  } finally {
    pendingMessage.value = ''
    chatBusy.value = false
    await scrollToLatest()
  }
}

function movePage(nextPage) {
  if (listLoading.value || chatBusy.value || nextPage < 1 || nextPage > totalPages.value) return
  selectedEssayId.value = null
  selectedEssay.value = null
  loadEssays(nextPage, true)
}

function askToDelete() {
  if (chatBusy.value) return
  deleteError.value = ''
  deleteOpen.value = true
}

async function deleteSelectedEssay() {
  if (selectedEssayId.value === null || deleteBusy.value || chatBusy.value) return
  deleteBusy.value = true
  deleteError.value = ''
  try {
    await api.deleteEssay(selectedEssayId.value)
    deleteOpen.value = false
    selectedEssayId.value = null
    selectedEssay.value = null
    messages.value = []
    viewMode.value = 'empty'
    await loadEssays(1, true)
  } catch (error) {
    if (handleUnauthorized(error)) {
      deleteOpen.value = false
    } else {
      deleteError.value = readableError(error)
    }
  } finally {
    deleteBusy.value = false
  }
}

function formatScore(value) {
  if (value === null || value === undefined) return '—'
  const number = Number(value)
  return Number.isFinite(number) ? number.toFixed(1) : '—'
}

function statusLabel(status) {
  return {
    NOT_EVALUATED: '未批改',
    PENDING: '批改中',
    COMPLETED: '已批改',
    FAILED: '批改未完成',
  }[status] || status || '未批改'
}

function formatDate(value) {
  if (!value) return '刚刚'
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return value
  return new Intl.DateTimeFormat('zh-CN', {
    month: 'numeric', day: 'numeric', hour: '2-digit', minute: '2-digit',
  }).format(date)
}

onMounted(bootstrap)
</script>

<template>
  <div v-if="booting" class="boot-screen" aria-live="polite">
    <div class="brand-mark">V</div>
    <div class="spinner" aria-hidden="true"></div>
    <p>正在准备你的写作空间…</p>
  </div>

  <div v-else-if="!currentUser" class="auth-screen">
    <div class="auth-layout">
      <section class="auth-hero">
        <div class="brand"><span class="brand-mark">V</span><span>verba<span class="brand-period">.</span></span></div>
        <div class="auth-hero-content">
          <p class="eyebrow">YOUR WRITING, REIMAGINED</p>
          <h1 class="hero-title">每一篇练习，<br /><em>都有下一步。</em></h1>
          <p class="hero-copy">写下你的想法，获得有结构的 IELTS 反馈。沿着每个问题继续追问，让进步看得见。</p>
          <div class="hero-points">
            <div><span>01</span><p>写作练习<br /><small>从真实题目开始</small></p></div>
            <div><span>02</span><p>结构化批改<br /><small>看清每一项分数</small></p></div>
            <div><span>03</span><p>持续对话<br /><small>把反馈变成方法</small></p></div>
          </div>
        </div>
        <p class="hero-footnote">A quiet space for better writing.</p>
      </section>

      <section class="auth-card">
        <div class="auth-card-inner">
          <p class="eyebrow">WELCOME TO VERBA</p>
          <h2>{{ authMode === 'login' ? '欢迎回来' : '创建你的账号' }}</h2>
          <p class="auth-subtitle">{{ authMode === 'login' ? '登录后，继续你的写作旅程。' : '几步之后，开始记录每一次进步。' }}</p>
          <div class="auth-tabs" role="tablist" aria-label="登录或注册">
            <button type="button" role="tab" :aria-selected="authMode === 'login'" :class="{ active: authMode === 'login' }" @click="switchAuth('login')">登录</button>
            <button type="button" role="tab" :aria-selected="authMode === 'register'" :class="{ active: authMode === 'register' }" @click="switchAuth('register')">注册</button>
          </div>

          <div v-if="bootError" class="status-banner status-error" role="alert">{{ bootError }}</div>
          <div v-if="authNotice" class="status-banner status-success" role="status">{{ authNotice }}</div>
          <div v-if="authError" class="status-banner status-error" role="alert">{{ authError }}</div>

          <form v-if="authMode === 'register'" class="auth-form" @submit.prevent="submitRegister">
            <label class="field"><span class="field-label">用户名</span><input v-model="authForm.username" class="input" name="username" autocomplete="username" maxlength="100" placeholder="为自己起一个名字" required /></label>
            <label class="field"><span class="field-label">邮箱</span><input v-model="authForm.email" class="input" name="email" type="email" autocomplete="email" placeholder="you@example.com" required /></label>
            <label class="field"><span class="field-label">密码</span><input v-model="authForm.password" class="input" name="password" type="password" autocomplete="new-password" placeholder="设置一个密码" required /></label>
            <button class="button button-primary auth-submit" type="submit" :disabled="authBusy">{{ authBusy ? '正在注册…' : '创建账号' }}<span aria-hidden="true">↗</span></button>
          </form>

          <form v-else class="auth-form" @submit.prevent="submitLogin">
            <label class="field"><span class="field-label">用户名</span><input v-model="authForm.username" class="input" name="username" autocomplete="username" placeholder="输入你的用户名" required /></label>
            <label class="field"><span class="field-label">密码</span><input v-model="authForm.password" class="input" name="password" type="password" autocomplete="current-password" placeholder="输入密码" required /></label>
            <div class="field"><label class="field-label" for="captcha">图片验证码</label><div class="captcha-row"><input id="captcha" v-model="authForm.captcha" class="input" name="captcha" autocomplete="off" maxlength="4" placeholder="输入图中字符" required /><img v-if="captchaSrc && !captchaFailed" class="captcha-image" :src="captchaSrc" alt="登录验证码，点击可刷新" title="点击换一张" @error="captchaFailed = true" @click="refreshCaptcha" /><span v-else class="captcha-unavailable">验证码暂不可用</span></div><button class="text-button" type="button" @click="refreshCaptcha">看不清？换一张</button></div>
            <button class="button button-primary auth-submit" type="submit" :disabled="authBusy">{{ authBusy ? '正在登录…' : '进入写作空间' }}<span aria-hidden="true">↗</span></button>
          </form>
          <p class="auth-note">批改与聊天记录只属于你的账号。</p>
        </div>
      </section>
    </div>
  </div>

  <div v-else class="app-shell">
    <header class="topbar">
      <div class="brand"><span class="brand-mark">V</span><span>verba<span class="brand-period">.</span></span></div>
      <div class="topbar-center">IELTS WRITING STUDIO <span> / </span> 你的写作空间</div>
      <div class="topbar-actions"><span class="user-avatar">{{ currentUser.username?.charAt(0)?.toUpperCase() || 'U' }}</span><span class="user-name">{{ currentUser.username }}</span></div>
    </header>

    <div class="workspace">
      <aside class="sidebar" aria-label="我的作文">
        <div class="sidebar-header"><div><p class="eyebrow">MY WORKSPACE</p><h2>我的作文</h2></div><span class="essay-count">{{ total }}</span></div>
        <button class="button button-primary new-essay-button" type="button" :disabled="chatBusy || evaluationBusy" @click="startEssay"><span aria-hidden="true">＋</span> 写一篇新作文</button>
        <div class="sidebar-section-title">最近练习 <span>按活跃时间排序</span></div>
        <div v-if="listError" class="status-banner status-error" role="alert">{{ listError }} <button class="text-button" type="button" @click="loadEssays(page)">重试</button></div>
        <div v-if="listLoading && essays.length === 0" class="loading-state"><div class="spinner"></div><span>正在加载作文…</span></div>
        <div v-else-if="essays.length === 0" class="sidebar-empty">还没有作文。<br />从第一篇开始吧。</div>
        <nav v-else class="essay-list" aria-label="作文列表">
          <button v-for="essay in essays" :key="essay.id" class="essay-item" :class="{ 'essay-item-active': selectedEssayId === essay.id }" type="button" :disabled="chatBusy || evaluationBusy" @click="openEssay(essay.id); revealMainPanel()">
            <span class="essay-item-top"><span class="essay-type">{{ essay.essayType === 'TASK_1' ? 'TASK 1' : 'TASK 2' }}</span><span class="essay-date">{{ formatDate(essay.lastActivityAt) }}</span></span>
            <span class="essay-title">{{ essay.title }}</span>
            <span class="essay-meta"><span>{{ essay.wordCount ?? 0 }} 词</span><span class="meta-dot">·</span><span>{{ statusLabel(essay.evaluationStatus) }}</span><span v-if="essay.overallScore !== null && essay.overallScore !== undefined" class="essay-score">{{ formatScore(essay.overallScore) }}</span></span>
          </button>
        </nav>
        <div class="sidebar-footer"><div class="pager"><button class="button button-ghost" type="button" :disabled="page <= 1 || listLoading || chatBusy || evaluationBusy" @click="movePage(page - 1)">← 上一页</button><span>{{ page }} / {{ totalPages || 1 }}</span><button class="button button-ghost" type="button" :disabled="page >= totalPages || listLoading || chatBusy || evaluationBusy" @click="movePage(page + 1)">下一页 →</button></div></div>
      </aside>

      <main ref="mainPanelElement" class="main-panel">
        <div v-if="viewMode === 'empty'" class="empty-state main-empty"><div class="empty-emblem">✦</div><p class="eyebrow">A FRESH PAGE</p><h1>一切从一篇作文开始。</h1><p>写下你的想法，让每一次练习都留下清晰的成长轨迹。</p><button class="button button-primary" type="button" @click="startEssay">开始写作 <span aria-hidden="true">↗</span></button></div>

        <div v-else-if="viewMode === 'editor'" class="editor-view">
          <div class="panel-header"><div><p class="eyebrow">NEW ESSAY</p><h1>开始新练习</h1><p>选择任务类型，输入题目与正文。批改会为这篇作文创建独立的聊天空间。</p></div></div>
          <div v-if="editorError" class="status-banner status-error" role="alert">{{ editorError }}</div>
          <div v-if="editorNotice" class="status-banner status-success" role="status">{{ editorNotice }}</div>
          <form class="editor-card" @submit.prevent="submitEvaluation">
            <div class="editor-topline"><span class="eyebrow">THE WRITING DESK</span><span>01 / 02</span></div>
            <label class="field"><span class="field-label">作文类型</span><select v-model="editor.essayType" class="select" :disabled="evaluationBusy || saveBusy"><option value="TASK_1">IELTS Writing · Task 1</option><option value="TASK_2">IELTS Writing · Task 2</option></select></label>
            <label class="field"><span class="field-label">作文题目</span><textarea v-model="editor.question" class="textarea question-input" rows="3" maxlength="10000" placeholder="把作文题目粘贴在这里…" :disabled="evaluationBusy || saveBusy" required></textarea></label>
            <label class="field"><span class="field-label">你的作文</span><textarea v-model="editor.content" class="textarea essay-input" rows="13" maxlength="15000" placeholder="在这里写下你的回答。每一个草稿，都是进步的开始…" :disabled="evaluationBusy || saveBusy" required></textarea></label>
            <div class="editor-actions"><span class="word-estimate">约 {{ estimatedWords }} 词 · 提交后以后端统计为准</span><div><button class="button button-secondary" type="button" :disabled="evaluationBusy || saveBusy" @click="saveWithoutEvaluation">{{ saveBusy ? '正在保存…' : '仅保存' }}</button><button class="button button-primary" type="submit" :disabled="evaluationBusy || saveBusy">{{ evaluationBusy ? 'AI 正在批改…' : '提交并批改' }} <span aria-hidden="true">↗</span></button></div></div>
          </form>
          <p v-if="evaluationBusy" class="editor-wait" role="status"><span class="spinner"></span> 作文已提交，AI 正在后台完成结构化批改。请勿重复点击。</p>
        </div>

        <div v-else class="detail-view">
          <div v-if="detailLoading" class="loading-state main-loading"><div class="spinner"></div><span>正在打开作文…</span></div>
          <div v-else-if="detailError" class="status-banner status-error" role="alert">{{ detailError }} <button class="text-button" type="button" @click="openEssay(selectedEssayId)">重试</button></div>
          <template v-else-if="selectedEssay">
            <div class="panel-header detail-header"><div><p class="eyebrow">{{ selectedEssay.essayType === 'TASK_1' ? 'WRITING TASK 1' : 'WRITING TASK 2' }} <span class="header-separator">/</span> ESSAY #{{ selectedEssay.id }}</p><h1>{{ selectedEssay.question }}</h1><div class="detail-meta"><span>{{ selectedEssay.wordCount ?? 0 }} 词</span><span>·</span><span>{{ statusLabel(selectedEssay.evaluationStatus) }}</span><span>·</span><span>{{ formatDate(selectedEssay.lastActivityAt || selectedEssay.createdAt) }}</span></div></div><button class="button button-ghost delete-button" type="button" :disabled="chatBusy" @click="askToDelete">删除整篇对话</button></div>

            <div v-if="chatNotice" class="status-banner status-warning" role="status">{{ chatNotice }}</div>
            <section class="surface-card essay-original"><div class="section-heading"><div><span class="section-index">01</span><h2>作文原文</h2></div><span>ORIGINAL ESSAY</span></div><p>{{ selectedEssay.content }}</p></section>

            <section class="evaluation-section"><div class="section-heading"><div><span class="section-index">02</span><h2>结构化批改</h2></div><span>IELTS EVALUATION</span></div>
              <template v-if="selectedEssay.evaluationStatus === 'COMPLETED'"><div class="score-grid"><div class="score-card overall-card"><span>综合分数</span><strong>{{ formatScore(selectedEssay.overallScore) }}</strong><small>OVERALL BAND</small></div><div class="score-card"><span>任务回应</span><strong>{{ formatScore(selectedEssay.taskScore) }}</strong><small>TASK RESPONSE</small></div><div class="score-card"><span>连贯与衔接</span><strong>{{ formatScore(selectedEssay.coherenceScore) }}</strong><small>COHERENCE</small></div><div class="score-card"><span>词汇运用</span><strong>{{ formatScore(selectedEssay.lexicalScore) }}</strong><small>LEXICAL RESOURCE</small></div><div class="score-card"><span>语法能力</span><strong>{{ formatScore(selectedEssay.grammarScore) }}</strong><small>GRAMMAR</small></div></div><div class="feedback-card"><div class="feedback-header"><h3>批改建议</h3><span v-if="selectedEssay.aiModel">{{ selectedEssay.aiModel }}</span></div><p>{{ selectedEssay.aiFeedback }}</p></div></template>
              <div v-else class="status-banner" :class="selectedEssay.evaluationStatus === 'FAILED' ? 'status-warning' : 'status-neutral'">{{ selectedEssay.evaluationStatus === 'FAILED' ? '作文已保存，但这次 AI 批改未完成。当前没有对已有作文重新批改的接口。' : selectedEssay.evaluationStatus === 'PENDING' ? 'AI 正在后台批改这篇作文。稍后重新打开即可查看结果。' : '这篇作文还没有初始 AI 评分。你仍可以在下方针对原文提问。' }}</div>
            </section>

            <section class="chat-section"><div class="section-heading"><div><span class="section-index">03</span><h2>继续提问</h2></div><span>YOUR CONVERSATION</span></div><p class="chat-intro">围绕这篇作文继续问，让每一条反馈都变得可执行。</p>
              <div ref="conversationElement" class="conversation" aria-live="polite"><div v-if="messagesLoading && messages.length === 0" class="loading-state"><div class="spinner"></div><span>正在读取聊天记录…</span></div><div v-else-if="messages.length === 0 && !pendingMessage" class="conversation-empty">暂无聊天记录。<br />可以先问 AI：我的文章最需要改进什么？</div><div v-if="messagesError" class="status-banner status-error" role="alert">{{ messagesError }} <button class="text-button" type="button" @click="refreshMessages()">重试</button></div><div v-for="message in messages" :key="message.id" class="message-row" :class="message.role === 'USER' ? 'message-user' : 'message-assistant'"><div class="message-avatar">{{ message.role === 'USER' ? (currentUser.username?.charAt(0)?.toUpperCase() || 'U') : 'V' }}</div><div class="message-body"><div class="message-byline">{{ message.role === 'USER' ? '你' : 'Verba AI' }} <span>{{ formatDate(message.createdAt) }}</span></div><div class="message-content">{{ message.content }}</div></div></div><div v-if="pendingMessage" class="message-row message-user message-pending"><div class="message-avatar">{{ currentUser.username?.charAt(0)?.toUpperCase() || 'U' }}</div><div class="message-body"><div class="message-byline">你 <span>正在发送</span></div><div class="message-content">{{ pendingMessage }}</div></div></div><div v-if="chatBusy" class="message-row message-assistant ai-thinking"><div class="message-avatar">V</div><div class="message-body"><div class="message-byline">Verba AI <span>正在思考</span></div><div class="loading-dots"><i></i><i></i><i></i></div></div></div></div>
              <div v-if="chatError" class="status-banner status-warning" role="alert">{{ chatError }}</div>
              <form class="composer" @submit.prevent="sendMessage"><textarea v-model="composerText" class="textarea" rows="3" maxlength="20000" placeholder="关于这篇作文，你还想知道什么？" :disabled="chatBusy" aria-label="聊天提问"></textarea><div class="composer-footer"><span>AI 回复为同步生成，可能需要稍等片刻。</span><button class="button button-primary" type="submit" :disabled="!canSendMessage">{{ chatBusy ? '等待回复…' : '发送提问' }} <span aria-hidden="true">↗</span></button></div></form>
            </section>
          </template>
        </div>
      </main>
    </div>

    <div v-if="deleteOpen" class="modal-overlay" role="presentation" @click.self="deleteOpen = false"><div class="modal-card" role="dialog" aria-modal="true" aria-labelledby="delete-title"><div class="modal-symbol">!</div><h2 id="delete-title">删除这篇作文及对话？</h2><p>作文原文、批改结果和该作文下的全部聊天消息都会被删除，无法从页面恢复。</p><div v-if="deleteError" class="status-banner status-error" role="alert">{{ deleteError }}</div><div class="modal-actions"><button class="button button-secondary" type="button" :disabled="deleteBusy" @click="deleteOpen = false">取消</button><button class="button button-danger" type="button" :disabled="deleteBusy" @click="deleteSelectedEssay">{{ deleteBusy ? '正在删除…' : '确认删除' }}</button></div></div></div>
  </div>
</template>
