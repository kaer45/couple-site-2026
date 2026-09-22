/**
 * AI 助手 API（/ai 页，个人域）
 * /api/agent/chat 返回 SSE 流，用 fetch + ReadableStream 读取
 * 事件行：data: {"delta":"增量"} / {"refs":[...]} / {"error":"..."} / [DONE]
 */
export async function streamChat(message, { onDelta, onRefs, onError, onDone }) {
  const token = localStorage.getItem('token')
  let resp
  try {
    resp = await fetch('/api/agent/chat', {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        ...(token ? { Authorization: `Bearer ${token}` } : {})
      },
      body: JSON.stringify({ message })
    })
  } catch (e) {
    onError?.('网络异常，请检查后端服务')
    return
  }
  if (!resp.ok) {
    onError?.(`请求失败（${resp.status}），请稍后重试`)
    return
  }
  const reader = resp.body.getReader()
  const decoder = new TextDecoder()
  let buf = ''
  try {
    for (;;) {
      const { done, value } = await reader.read()
      if (done) break
      buf += decoder.decode(value, { stream: true })
      let idx
      while ((idx = buf.indexOf('\n')) >= 0) {
        const line = buf.slice(0, idx).trim()
        buf = buf.slice(idx + 1)
        if (!line.startsWith('data:')) continue
        const data = line.slice(5).trim()
        if (!data || data === '[DONE]') {
          if (data === '[DONE]') onDone?.()
          continue
        }
        try {
          const obj = JSON.parse(data)
          if (obj.delta) onDelta?.(obj.delta)
          if (obj.refs) onRefs?.(obj.refs)
          if (obj.error) onError?.(obj.error)
        } catch (e) {
          /* 非 JSON 行忽略 */
        }
      }
    }
    // 流自然结束（无 [DONE]）也视为完成
    onDone?.()
  } catch (e) {
    onError?.('连接中断，请重试')
  }
}