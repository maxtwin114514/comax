# Comax 插件接口文档

> 适用版本：Comax 1.5.4o1beta 起（引擎 `PLUGIN_API_VERSION = 1`）
> 软件内同一份文档在：**设置 → 插件 → 插件接口文档**

插件（Plugin）是第三类扩展，和 Skill、MCP 的定位不同：

| 扩展 | 加什么 | 跑在哪 |
| --- | --- | --- |
| MCP | 给模型加**工具** | 独立进程，JSON-RPC |
| Skill | 给模型加**做法**（提示词/脚本说明） | 提示词检索 |
| **Plugin** | 给**界面和人**加东西：UI 位置、动效、图案、桌宠、提示词注入 | 前端 WebView |

---

## 1. 目录结构

```
~/.coomi/plugins/my-pet/
├── manifest.json   # 必需
├── index.js        # 默认入口，可用 manifest.entry 改
├── style.css       # 可选，自动注入
└── assets/icon.png # 可选，通过 /api/plugins/my-pet/asset/assets/icon.png 取
```

`~/.coomi` 在手机上对应 `$HOME/.coomi`（例如 `/data/data/<包名>/files/home/.coomi`）。
软件内的插件页会直接显示这个路径。

## 2. manifest.json

```json
{
  "id": "my-pet",
  "name": "我的桌宠",
  "version": "1.0.0",
  "author": "你",
  "description": "屏幕右下角的一只小黑猫",
  "entry": "index.js",
  "apiVersion": 1,
  "prompt": "回答时偶尔用「喵」结尾。",
  "slots": ["pet", "composer"],
  "exclusive": ["pet"],
  "permissions": ["ui", "prompt"],
  "enabledByDefault": false
}
```

| 字段 | 说明 |
| --- | --- |
| `id` | 唯一标识，建议与目录名一致（不一致会提示，但按 manifest 处理） |
| `entry` | 入口 JS，默认 `index.js` |
| `apiVersion` | 当前为 `1`。高于宿主版本会在界面上提示 |
| `prompt` | 静态注入的提示词（引擎拼进系统提示词，纯聊天模式除外） |
| `slots` | 使用哪些插槽，见下表；写错会被忽略并给出提示 |
| `exclusive` | 独占位，例如 `pet` 只能被一个已启用插件占用 |
| `permissions` | `ui` / `prompt` / `storage` / `network`，**仅用于展示与提醒** |
| `enabledByDefault` | 缺省 `false`：装进去默认不生效，必须用户手动开启 |

### 插槽

| 插槽 | 位置 |
| --- | --- |
| `topbar` | 顶栏右侧，模型名与上下文环之间 |
| `composer` | 输入框左侧，「+」与强度按钮之前 |
| `chat-overlay` | 会话区上方的绝对定位层（不拦事件，插件自己开 `pointer-events`） |
| `sidebar` | 侧边栏底部 |
| `pet` | 全局桌宠层（可拖动，层级低于弹窗） |
| `settings` | 设置页（预留给插件的自述块） |

## 3. index.js：`comax` API

入口文件整体会被 `new Function('comax', code)` 求值 —— **没有** iframe / worker 隔离，
它和页面同权（能访问 DOM 与 localStorage）。这也是为什么插件默认关闭、
必须由用户手动开启。

```js
// ① 注册插槽；host 是宿主给的容器元素
comax.slot('composer', (host) => {
  host.innerHTML = '<button>点我</button>'
  host.querySelector('button').onclick = () => comax.toast('喵')
  return () => { host.innerHTML = '' }   // 可选：卸载时清理
})

// ② 注入提示词（保存到引擎，下一轮生效）
comax.prompt('回答尽量简短。')

// ③ 注入 CSS（同一插件重复调用会替换）
comax.css('.my-badge { color: #5b6cff; }')

// ④ 事件钩子：turn-start / turn-end / message / ready
comax.on('turn-end', (payload) => console.log('这一轮结束', payload))

// ⑤ 桌宠（face 或 html 二选一，可拖动）
comax.pet({ face: '🐈‍⬛', size: 64, bob: 6, onClick: () => comax.toast('喵～') })

// ⑥ 私有存储（按插件命名空间隔离，底层是 localStorage）
const count = comax.storage.get('count', 0) || 0
comax.storage.set('count', count + 1)

// ⑦ 轻提示
comax.toast('插件已就绪')

// ⑧ 读取自带资源
fetch('/api/plugins/my-pet/asset/assets/icon.png')
```

### 生命周期

1. 引擎启动 / 前端加载 → `GET /api/plugins` 拿到清单；
2. 对每个**已启用且 manifest 无错**的插件：拉 `entry` → 求值 → 注册贡献；
3. 页面切换时插槽组件会调用插件返回的清理函数；
4. 关掉插件或点「重新扫描」→ 卸载旧实例、重新求值（不必重启引擎）。

插件抛出的异常只影响它自己：错误会显示在插件页对应条目下。

## 4. 引擎接口

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/api/plugins` | 清单 + 插槽占用 + 冲突 |
| POST | `/api/plugins/{id}/toggle` | `{ "enabled": true }` |
| POST | `/api/plugins/{id}/prompt` | `{ "prompt": "…" }`（动态提示词，覆盖 manifest） |
| GET | `/api/plugins/{id}/asset/{path}` | 静态资源（含路径穿越防护，单文件 ≤ 8 MB） |

开关与动态提示词保存在 `~/.coomi/config/plugins.json`：

```json
{ "schema_version": 1, "enabled": { "my-pet": true }, "prompts": { "my-pet": "…" } }
```

## 5. 边界（重要）

- **不能**调用模型、读写引擎内部文件、注册新工具 —— 那些请用 MCP / Skill。
- `slots` 只能是白名单里的名字；插件不能在未声明的插槽里渲染。
- 提示词注入总长度上限 8000 字符，超出会被截断并给出说明。
- 独占位冲突时两个插件都会被标出，必须在界面里关掉一个。
- 静态资源路径拒绝 `..`、绝对路径与符号链接逃逸。
- 纯聊天模式（推理强度「关闭」）不注入插件提示词：那一档只保留人格。

## 6. 调试

- 插件页能看到每个插件的 `errors`（manifest 问题）与运行错误（JS 异常）；
- 改完入口文件点「重新扫描」即可重载；
- 想看请求细节可以在插件里 `fetch` 自己的资产并打印，或用 `comax.toast` 输出。

## 7. 最小可用示例

`~/.coomi/plugins/hello/manifest.json`：

```json
{
  "id": "hello",
  "name": "打招呼",
  "slots": ["composer"],
  "permissions": ["ui"]
}
```

`~/.coomi/plugins/hello/index.js`：

```js
comax.slot('composer', (host) => {
  const button = document.createElement('button')
  button.textContent = '打招呼'
  button.style.cssText = 'padding:6px 10px;border-radius:999px;border:0;background:#5b6cff;color:#fff;font-size:12px'
  button.onclick = () => comax.toast('你好，我是插件')
  host.appendChild(button)
})
```

打开软件 → 设置 → 插件 → 重新扫描 → 打开「打招呼」→ 输入框左侧就会出现这颗按钮。
