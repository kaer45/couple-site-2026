/**
 * Markdown 渲染（markdown-it + KaTeX + highlight.js）
 *
 * 安全策略：`html: false` —— **不解析笔记里的原始 HTML**，这是最主要的 XSS 防线
 * （渲染结果会直接 v-html，所以绝不能开启 html）。
 * 链接协议由 markdown-it 自身白名单过滤（javascript: 之类会被拒绝）。
 *
 * 依赖：markdown-it / katex / highlight.js
 * 数学公式为自实现的轻量插件，未引入第三方 katex 插件，少一个不确定依赖。
 */
import MarkdownIt from 'markdown-it'
import katex from 'katex'
import hljs from 'highlight.js'

import 'katex/dist/katex.min.css'
import 'highlight.js/styles/github.css'

let md = null

function escapeHtml(text) {
  return String(text).replace(/[&<>"']/g, (c) => ({
    '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;'
  }[c]))
}

/** 渲染 LaTeX：失败不抛错，退回行内代码，避免一条公式搞挂整篇 */
function renderTex(tex, displayMode) {
  try {
    return katex.renderToString(tex, {
      displayMode,
      throwOnError: false,
      strict: false
    })
  } catch (e) {
    return `<code>${escapeHtml(tex)}</code>`
  }
}

/**
 * 数学公式插件：
 * - 行内：`$...$` 或 `\(...\)`
 * - 块级：`$$...$$` 或 `\[...\]`
 */
function katexPlugin(instance) {
  // ---------- 行内 $...$ / \(...\) ----------
  function mathInline(state, silent) {
    const src = state.src
    const isDollar = src[state.pos] === '$'
    const isParen = src.startsWith('\\(', state.pos)
    if (!isDollar && !isParen) return false
    const start = state.pos
    const max = state.posMax
    // 开分隔符后紧跟空白 → 当作普通字符（避免把 "价格 $5 和 $6" 误判成公式）
    if (start + 1 >= max || /\s/.test(src[start + 1])) return false

    const openLen = isDollar ? 1 : 2
    const closeMark = isDollar ? '$' : '\\)'
    let pos = start + openLen
    let end = -1
    while (pos < max) {
      const ch = src[pos]
      if (ch === '\\') { pos += 2; continue }   // 跳过转义，如 \$、\\)
      if (src.startsWith(closeMark, pos)) { end = pos; break }
      pos++
    }
    if (end < 0 || end === start + openLen) return false
    if (/\s/.test(src[end - 1])) return false   // 闭分隔符前是空白 → 不算公式

    const content = src.slice(start + openLen, end)
    if (!content.trim()) return false

    if (!silent) {
      const token = state.push('math_inline', 'math', 0)
      token.content = content
      token.markup = isDollar ? '$' : '\\('
    }
    state.pos = end + (isDollar ? 1 : 2)
    return true
  }

  // ---------- 块级 $$...$$ / \[...\] ----------
  function mathBlock(state, startLine, endLine, silent) {
    // 注意：行首偏移是 state.tShift（不是 tMarks），markdown-it 的 StateBlock 只有 bMarks/eMarks/tShift/sCount/bsCount
    const start = state.bMarks[startLine] + state.tShift[startLine]
    const firstLine = state.src.slice(start, state.eMarks[startLine])
    const isDollar = firstLine.startsWith('$$')
    const isBracket = firstLine.startsWith('\\[')
    if (!isDollar && !isBracket) return false

    const closeMark = isDollar ? '$$' : '\\]'
    let content
    let nextLine = startLine
    const single = firstLine.match(isDollar ? /^\$\$(.+?)\$\$\s*$/ : /^\\\[(.+?)\\\]\s*$/)
    if (single) {
      content = single[1]
    } else {
      // 多行：从首行分隔符之后开始收集，直到遇到以闭合符开头的行
      let body = firstLine.slice(2)
      nextLine = startLine + 1
      let closed = false
      while (nextLine < endLine) {
        const lineStart = state.bMarks[nextLine] + state.tShift[nextLine]
        const line = state.src.slice(lineStart, state.eMarks[nextLine])
        const idx = line.indexOf(closeMark)
        if (idx >= 0 && !line.slice(0, idx).trim()) {
          body += '\n' + line.slice(0, idx)
          closed = true
          break
        }
        body += '\n' + line
        nextLine++
      }
      if (!closed) return false
      content = body
    }
    if (!content.trim()) return false

    if (silent) return true
    const token = state.push('math_block', 'math', 0)
    token.content = content.trim()
    token.markup = isDollar ? '$$' : '\\['
    token.map = [startLine, nextLine + 1]
    state.line = nextLine + 1
    return true
  }

  instance.inline.ruler.before('escape', 'math_inline', mathInline)
  instance.block.ruler.before('fence', 'math_block', mathBlock, {
    alt: ['paragraph', 'reference', 'blockquote', 'list']
  })

  instance.renderer.rules.math_inline = (tokens, idx) => renderTex(tokens[idx].content, false)
  instance.renderer.rules.math_block = (tokens, idx) =>
    `<div class="katex-block">${renderTex(tokens[idx].content, true)}</div>\n`
}

/** 懒初始化单例 */
function instance() {
  if (md) return md
  md = new MarkdownIt({
    html: false,       // 安全：不解析原始 HTML
    linkify: true,     // 裸链接自动转 <a>
    breaks: true,      // 单个换行也换行（更符合中文笔记习惯）
    highlight(code, lang) {
      if (lang && hljs.getLanguage(lang)) {
        try {
          return `<pre class="hljs"><code>${hljs.highlight(code, { language: lang, ignoreIllegals: true }).value}</code></pre>`
        } catch (e) {
          /* 落回下面的默认分支 */
        }
      }
      return `<pre class="hljs"><code>${md.utils.escapeHtml(code)}</code></pre>`
    }
  })
  md.use(katexPlugin)
  return md
}

/**
 * 把 Markdown 源码渲染成 HTML
 * @param {string} text Markdown 源码
 * @returns {string} HTML（可安全用于 v-html：html:false + markdown-it 链接协议白名单）
 */
export function renderMarkdown(text) {
  if (!text) return ''
  try {
    return instance().render(text)
  } catch (e) {
    // 渲染异常时退回转义后的纯文本，绝不把原始内容直接交给 v-html
    return `<pre>${escapeHtml(text)}</pre>`
  }
}

/**
 * 列表卡片用的「渲染过的摘要」。
 *
 * 做法：解析出**块级 token**，取前面若干个 inline 块（标题/段落）单独用 renderInline 渲染。
 * - 只取 inline 块 ⇒ 代码块（fence）不会被拿来当摘要，块级元素（h1/table）也不会撑乱卡片
 * - 不按字符数硬截断 ⇒ 不会切出 `<strong>` 之类没闭合的残缺标签（视觉截断交给 CSS 的 line-clamp）
 * - token 会做两处调整，见 normalizeExcerptTokens
 *
 * @param {string} src 原始 Markdown 节选
 * @param {number} maxChars 累积多少字符后停止取块（默认 150）
 * @returns {string} 可直接 v-html 的 HTML；无内容时返回 ''
 */
export function renderExcerpt(src, maxChars = 150) {
  if (!src || !String(src).trim()) return ''
  const md = instance()

  let tokens
  try {
    tokens = md.parse(String(src), {})
  } catch (e) {
    return escapeHtml(plainText(String(src)))
  }

  const parts = []
  let used = 0
  for (const token of tokens) {
    if (token.type !== 'inline' || !token.content || !token.content.trim()) continue
    const children = normalizeExcerptTokens(token.children || [])
    parts.push(md.renderer.renderInline(children, md.options, {}))
    used += token.content.length
    if (used >= maxChars) break
  }

  if (!parts.length) {
    // 全是代码块等没有 inline 的内容：退回纯文本（顺手去掉 ``` 围栏标记）
    return escapeHtml(plainText(String(src)))
  }
  return parts.join(' ')
}

/**
 * 卡片摘要的 token 调整：
 * - **丢掉 image**：卡片里不去加载图片
 * - **软/硬换行换成空格**：卡片只有两行（CSS `line-clamp`），保留 `<br>` 会把内容撑成
 *   多行、和视觉截断打架，看起来就是"没渲染好"
 * - 公式（math_inline）**保持原样**，交给 KaTeX 正常渲染成数学符号
 */
function normalizeExcerptTokens(children) {
  return children
    .filter((c) => c.type !== 'image')
    .map((c) => (c.type === 'softbreak' || c.type === 'hardbreak')
      ? { type: 'text', content: ' ' }
      : c)
}

/** 把源码压成一行纯文本：去掉代码围栏标记、折叠空白 */
function plainText(src) {
  return String(src)
    .replace(/```[a-zA-Z0-9+#-]*/g, ' ')
    .replace(/\s+/g, ' ')
    .trim()
}
