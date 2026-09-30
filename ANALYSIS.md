# commonmark-java 扩展次序与覆盖规则代码交底

> 适用版本：本仓库 `0.30.1-SNAPSHOT`（根 `pom.xml:12`）。
> 取证输入（含标题、嵌套列表、链接和自定义扩展语法 `==mark==`）：

```markdown
# Analysis

- first item with a [link](https://example.com)
  - nested item with ==custom mark== here
```

`==custom mark==` 不是核心语法，由取证扩展注册的最小 inline parser 解析为自定义 `Mark` 节点。整条链路：

1. 构建期：`Parser.Builder.extensions(...)` 收集扩展
   - 扩展插入点：block parser factory、inline content parser factory、delimiter processor、link processor/marker、post processor、`InlineParserFactory` 整体替换
   - `enabledBlockTypes`、`includeSourceSpans`、`maxOpenBlockParsers`、`maxInlineNesting` 在此固化
2. 解析期：`Parser.parse` → `DocumentParser`（block 结构）→ `processInlines`（inline 结构）
3. 收尾期：按注册顺序跑 `PostProcessor`
4. 渲染期：`HtmlRenderer` 构造 `NodeRendererMap`（首个 renderer 获胜）+ 按顺序调用 `AttributeProvider`

下文每条结论均标注**实际文件与行号**，并区分「公开文档保证」（Javadoc/接口契约）与「当前实现细节」（内部类行为，不在公共 API 承诺范围内）。

---

## 1. 构建期：扩展如何被 `Parser.Builder` 收集

### 1.1 extension 入口与注册次序

- `Builder.extensions(Iterable)` 只对实现了 `ParserExtension` 的扩展调用 `extend(this)`，**按传入迭代顺序**执行；空安全但非 null：文件: commonmark/src/main/java/org/commonmark/parser/Parser.java:169。
- 各扩展插入点只是把对象追加进 `ArrayList`，没有去重、没有冲突检查（delimiter processor 除外，见 1.4）：
  - `customBlockParserFactory`：文件: commonmark/src/main/java/org/commonmark/parser/Parser.java:296
  - `customInlineContentParserFactory`：文件: commonmark/src/main/java/org/commonmark/parser/Parser.java:312
  - `customDelimiterProcessor`：文件: commonmark/src/main/java/org/commonmark/parser/Parser.java:334
  - `linkProcessor`：文件: commonmark/src/main/java/org/commonmark/parser/Parser.java:350
  - `linkMarker`（`HashSet<Character>`，无序、去重）：文件: commonmark/src/main/java/org/commonmark/parser/Parser.java:369
  - `postProcessor`：文件: commonmark/src/main/java/org/commonmark/parser/Parser.java:375
- **公开文档保证**：自定义 block factory 排在内置 factory **之前**，以便扩展覆盖核心语法：文件: commonmark/src/main/java/org/commonmark/parser/Parser.java:286（Javadoc "custom factories are applied before the built-in factories"）。实际拼装发生在 `DocumentParser.calculateBlockParserFactories`：文件: commonmark/src/main/java/org/commonmark/internal/DocumentParser.java:108（先 `new ArrayList<>(customBlockParserFactories)`，再追加核心 factory）。
- 构造 `Parser` 时会立刻用配置创建一次 inline parser，目的是尽早暴露非法配置：文件: commonmark/src/main/java/org/commonmark/parser/Parser.java:56。这意味着扩展注册冲突可能在 `build()` 时就抛异常，而不是首次 `parse` 时。

### 1.2 enabledBlockTypes 的边界

- 默认集合是 7 个核心 block 类型（有顺序的 `LinkedHashSet`）：`BlockQuote, Heading, FencedCodeBlock, HtmlBlock, ThematicBreak, ListBlock, IndentedCodeBlock`，见 文件: commonmark/src/main/java/org/commonmark/internal/DocumentParser.java:21。Builder 默认值取该集合：文件: commonmark/src/main/java/org/commonmark/parser/Parser.java:151。
- `enabledBlockTypes(...)` 只接受这 7 个 key，传扩展自定义 block 类型直接 `IllegalArgumentException`：校验在 文件: commonmark/src/main/java/org/commonmark/parser/Parser.java:209，实现为 `DocumentParser.checkEnabledBlockTypes`，文件: commonmark/src/main/java/org/commonmark/internal/DocumentParser.java:120。
- **边界细节（实现细节）**：该开关只过滤「核心 factory」。自定义 block factory 存在独立的 `customBlockParserFactories` 列表里，`calculateBlockParserFactories` 无条件先加入它们（文件: commonmark/src/main/java/org/commonmark/internal/DocumentParser.java:113），所以 `enabledBlockTypes(Set.of())` 会让 7 种核心 block 全部退化为普通文本，但**不会**禁用扩展 block。
- 注意 `ListBlock.class` 一个类型同时覆盖有序/无序列表；`ListItem`、`Paragraph`、`Document` 不在可选集合内（它们由其他 parser 隐式产生），可枚举项见文件: commonmark/src/main/java/org/commonmark/internal/DocumentParser.java:32。

### 1.3 inline 解析器的次序

- 自定义 `InlineContentParserFactory` 按字符触发；同一触发字符可有多个 parser，**自定义的按注册顺序先试，再试内置**。这是公开 Javadoc 保证：文件: commonmark/src/main/java/org/commonmark/parser/Parser.java:302。
- 实现细节：内置顺序固定为 Backslash → Backticks → Entity → Autolink → Html，自定义列表整体前置，文件: commonmark/src/main/java/org/commonmark/internal/InlineParserImpl.java:68。按触发字符分组在 `createInlineContentParsers()`：文件: commonmark/src/main/java/org/commonmark/internal/InlineParserImpl.java:166。
- 解析到特殊字符时，按列表逐个调用 `tryParse`，返回非 null 即采用并复位尝试位置：文件: commonmark/src/main/java/org/commonmark/internal/InlineParserImpl.java:253。
- Link processor 同理：自定义链按注册顺序在前，`CoreLinkProcessor` 兜底追加，文件: commonmark/src/main/java/org/commonmark/internal/InlineParserImpl.java:81；公开说明在 文件: commonmark/src/main/java/org/commonmark/parser/Parser.java:340。
- 触发字符集合（`specialCharacters`）还会被扩展触发字符、delimiter 字符、link marker 扩充，`[`、`]`、`!`、`\n` 恒为特殊字符：文件: commonmark/src/main/java/org/commonmark/internal/InlineParserImpl.java:146。

### 1.4 delimiter processor 的覆盖规则（与 factory 不同）

- 核心的 `*`、`_` processor 先放入 map，自定义的随后加入：文件: commonmark/src/main/java/org/commonmark/internal/InlineParserImpl.java:89（核心加入在 92-93 行，自定义在 94 行）。
- 同一开合字符且最小长度不同 → 包成 `StaggeredDelimiterProcessor` 按最短匹配优先；同字符同最小长度重复注册 → `IllegalArgumentException("Delimiter processor conflict ...")`，文件: commonmark/src/main/java/org/commonmark/internal/InlineParserImpl.java:98 与抛错点 文件: commonmark/src/main/java/org/commonmark/internal/InlineParserImpl.java:132。
- 公开契约见 Javadoc：文件: commonmark/src/main/java/org/commonmark/parser/Parser.java:320。**对比结论**：delimiter 是「同长度冲突即报错、不同长度共存」，而 inline content parser 是「静默地先注册先试」，两者覆盖语义不一样。

### 1.5 四个解析期边界开关

| 开关 | 默认 | 边界行为 | 证据 |
| --- | --- | --- | --- |
| `includeSourceSpans` | `NONE` | `NONE`/`BLOCKS`/`BLOCKS_AND_INLINES` 三态 | 枚举 文件: commonmark/src/main/java/org/commonmark/parser/IncludeSourceSpans.java:9；Builder 默认 文件: commonmark/src/main/java/org/commonmark/parser/Parser.java:154 |
| `maxOpenBlockParsers` | `100` | 负数抛异常；`Integer.MAX_VALUE` 表示不限；document 根 parser 不计数 | 文件: commonmark/src/main/java/org/commonmark/parser/Parser.java:243（含 Javadoc 边界） |
| `maxInlineNesting` | `100` | 负数抛异常；`MAX_VALUE` 表示不限；超限 delimiter/bracket 降级为纯文本；树可能比限制多深一层 | 文件: commonmark/src/main/java/org/commonmark/parser/Parser.java:278（Javadoc），执行点见下 |
| `enabledBlockTypes` | 7 个核心类型 | 仅核心 factory 受影响，见 1.2 | `commonmark/src/main/java/org/commonmark/internal/DocumentParser.java:120` |

- block 深度限制的执行点：`findBlockStart` 在 `openBlockParsers.size() > maxOpenBlockParsers` 时直接返回 null（新 block 退化为文本），注意是严格大于：文件: commonmark/src/main/java/org/commonmark/internal/DocumentParser.java:490。
- inline 深度限制：开关为非 `MAX_VALUE` 时才维护 `IdentityHashMap<Node,Integer>` 深度表：文件: commonmark/src/main/java/org/commonmark/internal/InlineParserImpl.java:59；bracket（link/image）包裹判定在 文件: commonmark/src/main/java/org/commonmark/internal/InlineParserImpl.java:472；delimiter 匹配判定在 文件: commonmark/src/main/java/org/commonmark/internal/InlineParserImpl.java:838。**实现细节（有 Javadoc 披露）**：由于判定发生在内容被包裹之前，最终树最多可比限制深一层，注释见文件: commonmark/src/main/java/org/commonmark/internal/InlineParserImpl.java:467。
- source span 的记录边界：
  - 每行只在非 `NONE` 时生成行级 `SourceSpan`：文件: commonmark/src/main/java/org/commonmark/internal/DocumentParser.java:352（`setLine`）。
  - 只有 `BLOCKS_AND_INLINES` 才把 sub span 随 `addLine` 交给 block parser：文件: commonmark/src/main/java/org/commonmark/internal/DocumentParser.java:461；inline 节点是否带 span 取决于 `SourceLines` 是否非空：文件: commonmark/src/main/java/org/commonmark/internal/InlineParserImpl.java:201，自动补 span 的逻辑在 文件: commonmark/src/main/java/org/commonmark/internal/InlineParserImpl.java:259。
  - **Document 根节点永远不记录 span**（避免整段文本都被覆盖），循环从 1 开始：文件: commonmark/src/main/java/org/commonmark/internal/DocumentParser.java:475。
  - `\0` 会被替换成 `\uFFFD`，替换发生在 span 长度计算之前但不改变长度：文件: commonmark/src/main/java/org/commonmark/internal/DocumentParser.java:597。

---

## 2. 解析期：DocumentParser → inline parser → postProcess

### 2.1 block 解析（String 与 Reader 两个入口）

- `Parser.parse(String)` 与 `parseReader(Reader)` 各建一个全新 `DocumentParser`（因此调用线程安全），分别见 文件: commonmark/src/main/java/org/commonmark/parser/Parser.java:86 与 文件: commonmark/src/main/java/org/commonmark/parser/Parser.java:115；工厂方法 `createDocumentParser()` 把所有固化配置传下去：文件: commonmark/src/main/java/org/commonmark/parser/Parser.java:122。
- String 入口按 `Characters.findLineBreak` 切行，`\r\n` 整体跳过：文件: commonmark/src/main/java/org/commonmark/internal/DocumentParser.java:133。
- Reader 入口走内部 `LineReader`（8192 字符缓冲，见 文件: commonmark/src/main/java/org/commonmark/internal/util/LineReader.java:19），逐行读取并把行内容长度与行终止符长度累加为 `inputIndex`：文件: commonmark/src/main/java/org/commonmark/internal/DocumentParser.java:155。
- 每行流程：`parseLine` 先让已打开的 container 尝试续行（`tryContinue`），再沿 factory 列表尝试 block start，最后把残余文本喂给 paragraph/code block：文件: commonmark/src/main/java/org/commonmark/internal/DocumentParser.java:210。`findBlockStart` 遍历 factory，第一个返回非 none 的胜出：文件: commonmark/src/main/java/org/commonmark/internal/DocumentParser.java:494。
- 收尾：`finalizeAndProcess()` 关闭所有 block → `processInlines()` → 返回根 `Document`：文件: commonmark/src/main/java/org/commonmark/internal/DocumentParser.java:566。

### 2.2 inline 解析

- `processInlines` 用与构建期不同的新 `Definitions`/context 创建 inline parser，并按 block finalize 顺序对每个 block parser 调 `parseInlines`：文件: commonmark/src/main/java/org/commonmark/internal/DocumentParser.java:507。
- `InlineParserImpl.parse(SourceLines, Node)` 循环 `parseInline()` 挂接子节点，最后 `processDelimiters(null)` 并合并相邻 Text：文件: commonmark/src/main/java/org/commonmark/internal/InlineParserImpl.java:184。

### 2.3 postProcess 的次序与根替换语义

- `Parser.postProcess` 严格按列表顺序调用，且**每一步都把上一步的返回值作为下一步输入**，最终返回值不要求是原来的 `Document`：文件: commonmark/src/main/java/org/commonmark/parser/Parser.java:135。
- `PostProcessor` 公开契约只写「may be a modified node argument」，即返回新根是被允许的：文件: commonmark/src/main/java/org/commonmark/parser/PostProcessor.java:7。
- **重要推论**：post processor 运行在 span 记录与 inline 解析完成之后；若它用全新节点替换根且不搬运 span，后续消费者看到的就是「span 缺失」而不是「span 错误」——库不会校验 span 与原始输入的一致性。这是风险 A 的根因（见第 4 节）。

---

## 3. 渲染期：NodeRendererMap 与 AttributeProvider

### 3.1 factory → renderer 的注册次序

- `HtmlRenderer` 构造时把自定义 factory 全部加入，再把 `CoreHtmlNodeRenderer` **显式追加为最后一个**，注释明示客户可借此覆盖核心节点：文件: commonmark/src/main/java/org/commonmark/renderer/html/HtmlRenderer.java:40（`MarkdownRenderer` 同样把 core 放最后：文件: commonmark/src/main/java/org/commonmark/renderer/markdown/MarkdownRenderer.java:35；`TextContentRenderer` 亦然：文件: commonmark/src/main/java/org/commonmark/renderer/text/TextContentRenderer.java:21）。
- 每次 `render` 新建 `RendererContext`，实例化所有 node renderer 并依次 `nodeRendererMap.add(...)`：文件: commonmark/src/main/java/org/commonmark/renderer/html/HtmlRenderer.java:239。

### 3.2 多个 renderer 声明同一节点类型：谁获胜

- **第一个**为某节点类型注册的 renderer 获胜：`NodeRendererMap.add` 用 `putIfAbsent`，见 文件: commonmark/src/main/java/org/commonmark/internal/renderer/NodeRendererMap.java:19（注释 "The first node renderer for a node type wins"，putIfAbsent 在第 23 行）。
- 由于核心 renderer 最后注册，自定义 renderer 总是先于核心；公开 Javadoc 也承诺「factory added first wins, default rendering comes last」：文件: commonmark/src/main/java/org/commonmark/renderer/html/HtmlRenderer.java:195。
- 分发是**精确 `getClass()` 匹配，不沿父类回退**：文件: commonmark/src/main/java/org/commonmark/internal/renderer/NodeRendererMap.java:27。自定义节点若没人注册 renderer，就静默不输出任何标签（其子节点也不会被自动访问，除非胜出 renderer 自己回调 `context.render(child)`，参考 ins 扩展 文件: commonmark-ext-ins/src/main/java/org/commonmark/ext/ins/internal/InsHtmlNodeRenderer.java:26）。
- 落败 renderer 并非完全不参与：`beforeRoot`/`afterRoot` 会对**所有**已注册 renderer 调用，与覆盖结果无关：文件: commonmark/src/main/java/org/commonmark/internal/renderer/NodeRendererMap.java:35。
- 同一个 renderer factory 每次 render 都会被重新实例化（无单例缓存）：文件: commonmark/src/main/java/org/commonmark/renderer/html/HtmlRenderer.java:247。

### 3.3 AttributeProvider 的次序与边界

- provider 在每次渲染时按 factory 列表顺序创建：文件: commonmark/src/main/java/org/commonmark/renderer/html/HtmlRenderer.java:242。
- 每次标签求值时，`extendAttributes` 先复制核心默认属性到 `LinkedHashMap`，再**按注册顺序**串行调用所有 provider，后执行者能看到/覆盖先执行者写入的键：文件: commonmark/src/main/java/org/commonmark/renderer/html/HtmlRenderer.java:283 与循环体 文件: commonmark/src/main/java/org/commonmark/renderer/html/HtmlRenderer.java:312。
- 核心 renderer 在每个标签点通过 `context.extendAttributes(node, tagName, defaults)` 取值：文件: commonmark/src/main/java/org/commonmark/renderer/html/CoreHtmlNodeRenderer.java:300。
- **实现细节**：provider 只有被某个实际执行的 node renderer 主动调用 `extendAttributes` 时才会触发；若该节点的胜出 renderer 完全不调 `context.extendAttributes`（自定义 renderer 可以这么写），则任何 provider 都收不到该节点。接口契约本身只承诺「修改传入 map」，未承诺调用顺序之外的行为：文件: commonmark/src/main/java/org/commonmark/renderer/html/AttributeProvider.java:10。同一节点多个标签（如 code block 的 `pre`/`code`）会被调用多次，这是公开 Javadoc：文件: commonmark/src/main/java/org/commonmark/renderer/html/AttributeProvider.java:18。
- AttributeProvider 是 HTML renderer 专属扩展点；Markdown renderer 与 TextContent renderer 没有对应机制（它们的 context 类型上没有 `extendAttributes`）。

---

## 4. 三个独立风险与复现

三个风险彼此独立，复现全部固化在取证测试
`commonmark-integration-test/src/test/java/org/commonmark/integration/proof/UnderstandingProofIntegrationTest.java`（不改任何生产 API）。

### 风险 A：post processor 替换根节点后，source span 不再可信

- 根因链：span 只由 parser 写入（文件: commonmark/src/main/java/org/commonmark/internal/DocumentParser.java:475）→ postProcess 原样替换返回值（文件: commonmark/src/main/java/org/commonmark/parser/Parser.java:135）→ `Node.setSourceSpans` 允许任意覆盖（文件: commonmark/src/main/java/org/commonmark/node/Node.java:132）。
- 复现：`RootReplacingPostProcessor` 返回一棵新建的 `Document → Paragraph → Text`，不拷贝 span。`postProcessorReplacingRootProducesUntrustworthySpans` 断言三个层级 `getSourceSpans()` 全为空。取证输出：

```
== risk A: spans after root replacement ==
node: depth=0 type=Document spans=no-span
node: depth=1 type=Paragraph spans=no-span
node: depth=2 type=Text spans=no-span
```

- 结论：**「没有 span」是可信信号（说明非 parser 原生）；「有 span」也只代表某个环节调过 `setSourceSpans`**。post processor 搬运/伪造的 span 不会被校验。公开文档只保证 parser 产出的 span 语义（文件: commonmark/src/main/java/org/commonmark/node/SourceSpan.java:5），不保证 postProcess 之后仍对应原始输入——这属于实现细节导致的信任边界，维护组应把「post processor 之后的 span 不可作为安全定位依据」写进使用约束。

### 风险 B：renderer factory 注册顺序改变覆盖结果

- 根因：`putIfAbsent` 首个获胜（文件: commonmark/src/main/java/org/commonmark/internal/renderer/NodeRendererMap.java:23）+ 核心 renderer 永远最后（文件: commonmark/src/main/java/org/commonmark/renderer/html/HtmlRenderer.java:43）。
- 复现：两个 factory 分别声明渲染同一个自定义 `Mark` 类型（tag 记为 `first`/`second`），仅交换注册顺序。取证输出：

```
== risk B: order [first, second] ==
renderer: winner=first
== risk B: order [second, first] ==
renderer: winner=second
```

- 边界补充：
  - 顺序只在「同一轮 Builder 配置」内确定；`extensions(Iterable)` 按入参迭代顺序生效（文件: commonmark/src/main/java/org/commonmark/renderer/html/HtmlRenderer.java:216），所以把扩展放进 `HashSet` 传参会让覆盖结果随哈希枚举顺序漂移——应使用 `List` 这类稳定顺序容器。
  - 落败 renderer 的 `beforeRoot/afterRoot` 仍会执行（文件: commonmark/src/main/java/org/commonmark/internal/renderer/NodeRendererMap.java:34），若它在回调里改全局状态，「输了渲染」不等于「完全不生效」，排查时容易漏。
  - 注册次序是**公开文档保证**（Javadoc 明确 first wins）；但「落败方 beforeRoot 仍执行」「精确 class 匹配」属于实现细节。

### 风险 C：Reader 入口遇到 BOM / 分块边界时，位置与 String 入口的差异

- 复现 1（分块边界）：自定义 `ChunkedReader` 强制每次最多吐出 2 个字符，输入 `"# a\r\n# b"` 使 CR、LF 落在不同底层 chunk。对比两个入口打印出的 span：

```
== risk C: Reader entry (2-char chunks, CRLF straddles a boundary) ==
node: depth=1 type=Heading spans=L0:C0:I0+3
node: depth=2 type=Text spans=L0:C2:I2+1
node: depth=1 type=Heading spans=L1:C0:I5+3
```

  与 String 入口逐行一致（`readerTree` 与 `stringTree` 用 `isEqualTo` 断言相等）。CR 跨 chunk 的处理在 `LineReader` 中靠 `cr` 状态在下一轮读循环里判定 CRLF 还是孤立 CR：文件: commonmark/src/main/java/org/commonmark/internal/util/LineReader.java:50；Reader 路径 `inputIndex` 按「行长度 + 行终止符长度」累计：文件: commonmark/src/main/java/org/commonmark/internal/DocumentParser.java:155，与 String 路径按实际字符下标切分（文件: commonmark/src/main/java/org/commonmark/internal/DocumentParser.java:133）在数值上对齐。

- 复现 2（BOM）：输入 `"\uFEFF# heading"`。两个入口结果完全相同，且都**不剥除 BOM**：BOM 占第 0 列，`#` 退到第 1 列，ATX heading 工厂要求下一个非空白字符恰为 `#`（文件: commonmark/src/main/java/org/commonmark/internal/HeadingParser.java:51），整行因此被解析成 `Paragraph`，span 为 `L0:C0:I0+10`。
- 公开文档保证与实现细节的分界：
  - **公开文档**：Javadoc 明确要求调用方在把含 BOM 的文件交给库之前自行跳过 BOM，并点名 Commons IO 的 `BOMInputStream`：文件: commonmark/src/main/java/org/commonmark/parser/Parser.java:105。也就是说「BOM 不被特殊处理」是使用者需要承担的契约，不是 bug。
  - **实现细节**：两条入口路径内部完全不同（String 直接切串；Reader 走 8192 缓冲的 `LineReader`，文件: commonmark/src/main/java/org/commonmark/internal/util/LineReader.java:19），目前它们产出的 span 恰好逐值一致，但没有公共契约约束未来版本必须保持这种逐值一致（`inputIndex` 本身就是为 Reader 场景新增的字段，旧构造器会把它置 0：文件: commonmark/src/main/java/org/commonmark/node/SourceSpan.java:41）。列/长度以 UTF-16 code unit 计量而非字节/码点：文件: commonmark/src/main/java/org/commonmark/node/SourceSpan.java:23。

---

## 5. 公开文档保证 vs 当前实现细节（速查）

**公开文档保证（可依赖）**

- 自定义 block factory 先于内置 factory：`Parser.java:286`
- 自定义 inline content parser 按注册顺序先于内置尝试：`Parser.java:302`
- delimiter 同字符不同最小长度共存、同最小长度冲突报错：`Parser.java:320`
- 两个深度限制的默认值 100、语义（超限降级为文本）、`MAX_VALUE` 不限：文件: commonmark/src/main/java/org/commonmark/parser/Parser.java:231、文件: commonmark/src/main/java/org/commonmark/parser/Parser.java:251
- renderer factory 先注册先赢、核心 renderer 最后：`HtmlRenderer.java:195`
- AttributeProvider 通过 map 修改默认属性、可被多标签多次调用：`AttributeProvider.java:10`
- BOM 需调用方自行跳过：`Parser.java:105`
- `parse`/`parseReader` 每次调用使用全新解析状态（线程安全）：文件: commonmark/src/main/java/org/commonmark/parser/Parser.java:81、文件: commonmark/src/main/java/org/commonmark/parser/Parser.java:109

**当前实现细节（升级时需重新验证）**

- 核心 factory 的固定枚举顺序（`BlockQuote` 在前、`IndentedCodeBlock` 在末）：`DocumentParser.java:21`
- `enabledBlockTypes` 不过滤自定义 block factory：`DocumentParser.java:113`
- inline 深度超限时树最多比限制深一层：`InlineParserImpl.java:467`
- Document 节点永不挂 span、`\0` 静默替换为 U+FFFD：`DocumentParser.java:475`、`DocumentParser.java:597`
- `NodeRendererMap` 精确 class 匹配、落败方仍收到 before/afterRoot 回调：`NodeRendererMap.java:28`、`NodeRendererMap.java:34`
- 自定义 node renderer 不调用 `extendAttributes` 时 provider 被整体旁路：`HtmlRenderer.java:312`
- String 与 Reader 入口 span 逐值一致；`LineReader` 8192 缓冲：`LineReader.java:19`

---

## 6. 取证测试说明

- 文件：`commonmark-integration-test/src/test/java/org/commonmark/integration/proof/UnderstandingProofIntegrationTest.java`
- 最小扩展同时实现 `Parser.ParserExtension` 与 `HtmlRenderer.HtmlRendererExtension`：一个触发字符 `=` 的 `InlineContentParserFactory`（解析 `==...==` 为自定义 `Mark` 节点）、一个 `AttributeProviderFactory`（加 `data-proof` 属性）、一个 `HtmlNodeRendererFactory`（渲染 `<mark>` 并主动回调 `context.render`）。
- 取证手段仅限：AST 直接遍历（节点类型、深度、逐行 `SourceSpan`）、AttributeProvider 回调记录、NodeRenderer 回调记录。**不**从最终 HTML 字符串反推调用链；唯一对字符串的断言只验证标签闭合这种表层事实。
- 四个测试：
  1. `printsNodeOrderSpansAndAttributes`：打印节点顺序/span/属性回调（第 0 节输入）
  2. `postProcessorReplacingRootProducesUntrustworthySpans`：风险 A
  3. `firstNodeRendererFactoryForNodeTypeWins`：风险 B（正序/逆序两轮）
  4. `readerAndStringEntryPointsAgreeExceptForBomHandling`：风险 C（2 字符 chunk 的 CRLF 跨界 + BOM 跨界）
- 运行方式：

```bash
./mvnw -q -pl commonmark-integration-test -Dtest='*UnderstandingProof*' test
./mvnw -q test
```
