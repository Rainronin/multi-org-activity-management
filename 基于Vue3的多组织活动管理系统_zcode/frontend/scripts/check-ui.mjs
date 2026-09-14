// check:ui —— UI 规范 14.1 最小门禁（P0.2 建立的静态检查，随实际写法逐步补齐）。
// 覆盖：tokens.css 完整性（未定义引用、块内重复定义、自引用循环）；
// 业务文件中的颜色字面量、固定设计尺寸字面量、字体属性字面量、z-index、
// transition/animation 时长字面量、!important 与内联 style 绕过；
// 以及 Element Plus 组件的模板使用与显式导入是否一致。
// 结构值（0、auto、none、100%、min-content 等）、SVG 绘图坐标与数据百分比不受限。
// 限制：无法静态判断的组件数值 props 与数据几何由代码审核补足。
import { readFileSync, readdirSync, statSync } from 'node:fs'
import { join, relative } from 'node:path'

const root = process.cwd()
const problems = []

const VALUE_SOURCE = 'src/styles/tokens.css'
const ADAPTER_FILES = new Set(['src/styles/element-plus.css', 'src/styles/base.css'])

function listFiles(dir, extension) {
  const result = []
  for (const entry of readdirSync(dir)) {
    const full = join(dir, entry)
    if (statSync(full).isDirectory()) {
      result.push(...listFiles(full, extension))
    } else if (extension.some((ext) => entry.endsWith(ext))) {
      result.push(full)
    }
  }
  return result
}

function addProblem(file, line, message) {
  problems.push(`${relative(root, file)}:${line} ${message}`)
}

// ---------- 1. tokens.css 完整性 ----------
const tokensPath = join(root, VALUE_SOURCE)
const tokensText = readFileSync(tokensPath, 'utf8')

const defined = new Set()
for (const match of tokensText.matchAll(/(--ui-[\w-]+)\s*:/g)) {
  defined.add(match[1])
}

for (const match of tokensText.matchAll(/var\(\s*(--ui-[\w-]+)/g)) {
  if (!defined.has(match[1])) {
    addProblem(tokensPath, 0, `token ${match[1]} 被引用但未定义`)
  }
}

// 同一声明块内重复定义视为并列值源；跨选择器与媒体查询的有意覆盖不误判。
const blockPattern = /([^{}]+)\{([^{}]*)\}/g
for (const block of tokensText.matchAll(blockPattern)) {
  const seen = new Map()
  for (const declaration of block[2].matchAll(/(--[\w-]+)\s*:/g)) {
    const name = declaration[1]
    if (seen.has(name)) {
      addProblem(tokensPath, 0, `token ${name} 在选择器${block[1].trim().slice(0, 60)} 内重复定义`)
    }
    seen.set(name, true)
  }
}

for (const match of tokensText.matchAll(/(--ui-[\w-]+)\s*:[^;]*var\(\s*\1[\s,)]/g)) {
  addProblem(tokensPath, 0, `token ${match[1]} 直接引用自身，形成循环`)
}

// ---------- 2. 业务文件扫描 ----------
const COLOR_LITERAL = /#[0-9a-fA-F]{3,8}\b|\b(?:rgba?|hsla?|oklch|oklab)\s*\(\s*[\d.]/g
const SIZE_PROPERTIES =
  'padding|margin|gap|border-radius|width|height|min-width|max-width|min-height|max-height|' +
  'top|right|bottom|left|inset|border-width|outline-width|outline-offset|translate|font-size|' +
  'line-height|letter-spacing|font-weight|box-shadow|text-shadow|z-index|flex-basis'
// 尺寸检查仅针对 px/rem/em/ms/s 等设计单位；视口单位（vh/vw/dvh）与百分比视为结构值，
// 仅在 tokens.css 内部参与布局计算。0 与 auto 等结构值不受限。
const SIZE_LITERAL = new RegExp(`(?:${SIZE_PROPERTIES})\\s*:[^;{}]*\\d(?:\\.\\d+)?(?:px|rem|em|ms|s)\\b`, 'g')
const FONT_FAMILY_LITERAL = /font-family\s*:\s*(?!"?\s*var\()[^;{}]*[;'{}]/g
const MOTION_LITERAL = /(?:transition|animation)(?:-duration)?\s*:[^;{}]*\d(?:\.\d+)?m?s\b/g
const INLINE_STYLE_BYPASS = /(?:^|\s)style="[^"]*(?:px|#[0-9a-fA-F]{3}|rgb)/g
// 组件数值 props 同样承载设计数值（R06）：width／height 等属性不允许像素字面量，
// 应绑定 token（width="var(--ui-dialog-width)" 之类不受限）。SVG 绘图尺寸不在检查范围。
// 表格列宽例外（见下方 scanComponentProps 注释）：只有 ElTableColumn 允许数值列宽。
const TABLE_COLUMN_TAG = /^<ElTable(Column)?\b/
const COMPONENT_SIZE_PROP = /<(?!svg)[A-Za-z][\w.-]*[^>]*?\s:?(min-width|max-width|width|height|min-height|max-height)="(\d+(?:\.\d+)?(?:px)?)"/g

function scanCss(text, file, offset = 0) {
  for (const match of text.matchAll(COLOR_LITERAL)) {
    addProblem(file, offset + lineOf(text, match.index), `颜色字面量 ${match[0]}，必须使用 token`)
  }
  for (const match of text.matchAll(SIZE_LITERAL)) {
    addProblem(file, offset + lineOf(text, match.index), `固定设计尺寸 ${match[0].trim().slice(0, 60)}，必须使用 token`)
  }
  for (const match of text.matchAll(FONT_FAMILY_LITERAL)) {
    addProblem(file, offset + lineOf(text, match.index), `font-family 字面量，必须引用 --ui-font-family-*`)
  }
  for (const match of text.matchAll(MOTION_LITERAL)) {
    addProblem(file, offset + lineOf(text, match.index), `动效时长字面量 ${match[0].trim().slice(0, 60)}，必须使用 motion token`)
  }
  for (const match of text.matchAll(/!important/g)) {
    addProblem(file, offset + lineOf(text, match.index), '禁止用 !important 解决主题覆盖')
  }
}

function lineOf(text, index) {
  return text.slice(0, index).split('\n').length
}

const businessFiles = [
  ...listFiles(join(root, 'src'), ['.vue', '.css']),
  ...[...ADAPTER_FILES].map((file) => join(root, file)),
].filter((file) => relative(root, file).split('\\').join('/') !== VALUE_SOURCE)

for (const file of businessFiles) {
  const text = readFileSync(file, 'utf8')
  scanCss(text, file)
  for (const match of text.matchAll(INLINE_STYLE_BYPASS)) {
    addProblem(file, lineOf(text, match.index), `内联 style 疑似绕过 token：${match[0].trim().slice(0, 60)}`)
  }
  for (const match of text.matchAll(COMPONENT_SIZE_PROP)) {
    if (TABLE_COLUMN_TAG.test(match[0])) {
      continue
    }
    addProblem(file, lineOf(text, match.index), `组件尺寸 props 字面量 ${match[0].slice(-40)}，应绑定 token`)
  }
  if (file.endsWith('.vue')) {
    checkElementPlusImports(text, file)
  }
}

/**
 * Element Plus 组件按需显式导入：模板里用到却未导入的组件会被当成未知标签，
 * 表现为表格列不渲染、插槽拿到 undefined（P3「我的任务」曾整页报错），静态门禁拦住这类回归。
 */
function checkElementPlusImports(text, file) {
  const templateStart = text.indexOf('<template>')
  if (templateStart < 0) {
    return
  }
  const template = text.slice(templateStart)
  const setup = text.slice(0, templateStart)
  const imported = new Set()
  for (const block of setup.matchAll(/import\s*\{([^}]+)\}\s*from\s*'element-plus'/g)) {
    for (const item of block[1].split(',')) {
      const name = item.trim().split(/\s+as\s+/)[0].trim()
      if (name) {
        imported.add(name)
      }
    }
  }
  const seen = new Set()
  for (const match of template.matchAll(/<(El[A-Za-z]+)[\s/>]/g)) {
    const tag = match[1]
    if (imported.has(tag) || seen.has(tag)) {
      continue
    }
    seen.add(tag)
    addProblem(file, lineOf(text, templateStart + match.index), `模板使用 <${tag}> 但未从 element-plus 显式导入`)
  }
}

// ---------- 3. 规则自检（U06）----------
// 静态检查本身也会写错：这里留一组能命中反例的小检查，规则被改坏时立即失败，
// 而不是等到正式页面出现像素字面量或漏检才被发现。
function collect(text) {
  const found = []
  const pick = (pattern, label) => {
    for (const match of text.matchAll(pattern)) {
      found.push(label(match))
    }
  }
  pick(COLOR_LITERAL, (match) => `颜色字面量 ${match[0]}`)
  pick(SIZE_LITERAL, (match) => `固定设计尺寸 ${match[0].trim()}`)
  pick(FONT_FAMILY_LITERAL, () => 'font-family 字面量')
  pick(MOTION_LITERAL, (match) => `动效时长字面量 ${match[0].trim()}`)
  pick(/!important/g, () => '禁止用 !important')
  for (const match of text.matchAll(COMPONENT_SIZE_PROP)) {
    if (!TABLE_COLUMN_TAG.test(match[0])) found.push('组件尺寸 props 字面量')
  }
  pick(INLINE_STYLE_BYPASS, () => '内联 style 疑似绕过 token')
  return found
}
const SELF_TEST = [
  { text: '.a { color: #ff6b2c; }', expect: '颜色字面量' },
  { text: '.a { color: var(--ui-color-accent); }', expect: null },
  { text: '.a { padding: 12px; }', expect: '固定设计尺寸' },
  { text: '.a { gap: var(--ui-space-2); }', expect: null },
  { text: '.a { transition: all .3s; }', expect: '动效时长字面量' },
  { text: '.a { transition: all var(--ui-motion-fast); }', expect: null },
  { text: '.a { color: red !important; }', expect: '禁止用 !important' },
  { text: '.a { font-family: "Microsoft YaHei", sans-serif; }', expect: 'font-family 字面量' },
  { text: '<ElButton :width="12">确定</ElButton>', expect: '组件尺寸 props 字面量' },
  { text: '<ElDialog width="480">', expect: '组件尺寸 props 字面量' },
  { text: '<ElDialog width="var(--ui-dialog-width)">', expect: null },
  { text: '<ElTableColumn min-width="100" />', expect: null }, // 表格列宽例外：Element Plus 需要数值
  { text: '<svg viewBox="0 0 32 32"><path d="M8 8h5" /></svg>', expect: null },
  { text: '<div style="margin-top: 12px">', expect: '内联 style 疑似绕过 token' },
]

for (const [index, item] of SELF_TEST.entries()) {
  const found = collect(item.text)
  const ok = item.expect === null ? found.length === 0 : found.some((message) => message.includes(item.expect))
  if (!ok) {
    problems.push(
      `check:ui 自检用例 ${index + 1} 未按预期：${item.text}（期望 ${item.expect ?? '不命中'}，实际 ${found.join('、') || '不命中'}）`,
    )
  }
}

if (problems.length > 0) {
  console.error(`check:ui 失败，共 ${problems.length} 处：`)
  for (const problem of problems) {
    console.error(`  ${problem}`)
  }
  process.exit(1)
}
console.log('check:ui PASS：token 完整性与业务样式字面量检查通过。')
