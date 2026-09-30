# commonmark-java 解析与渲染流水线代码级交底

本文面向维护组，沿一条真实输入走完整条流水线：`Parser.Builder` 收集扩展 → `DocumentParser` 块解析 → inline 解析 → `postProcess` → `HtmlRenderer` 的 `NodeRendererMap` 与 `AttributeProvider`。每个关键结论都附文件与行号；文末区分**公开文档保证**（javadoc 明确承诺）与**当前实现细节**（可随版本变化）。

## 0. 取证输入与取证测试

统一使用下面这段输入（含标题、嵌套列表、链接、自定义 `@mention` 扩展语法）：

```markdown
# Proof Heading

- item one
  - nested with [a link](https://example.com)
- item two mentions @alice
```

配套取证测试为 `commonmark-integration-test/src/test/java/org/commonmark/integration/UnderstandingProofIntegrationTest.java`，它自定义了最小扩展 `ProofExtension`（同时实现 `Parser.ParserExtension` 与 `HtmlRenderer.HtmlRendererExtension`），通过公共扩展点插桩记录：parser 产出的节点先序顺序与 source span、attribute provider 的每次调用、renderer 覆盖结果。测试不修改任何生产 API，也不从 renderer 的最终字符串反推调用链——节点顺序与 span 直接读自 AST，attribute 记录自 `AttributeProvider.setAttributes` 回调本身。

实测输出（`BLOCKS_AND_INLINES` 模式下先序遍历）：

```
Document []
Heading [SourceSpan{line=0, column=0, input=0, length=15}]
Text [SourceSpan{line=0, column=2, input=2, length=13}]
BulletList [line=2..4 三个 span]
ListItem / Paragraph / Text ...
Link [SourceSpan{line=3, column=16, input=44, length=29}]
Mention [SourceSpan{line=4, column=20, input=94, length=6}]
```

HTML 侧输出 `<h1>Proof Heading</h1>`、嵌套 `<ul>`、`<a href="https://example.com" data-proof="link">`（`data-proof` 由 attribute provider 注入）、`<span class="mention">@alice</span>`（自定义 node renderer 产出）。

## 1. Parser.Builder：扩展收集阶段

- `Parser.builder().extensions(...)` 只接受 `ParserExtension` 类型的扩展并回调 `extend(builder)`，其它类型被静默忽略。文件: `commonmark/src/main/java/org/commonmark/parser/Parser.java:169`
- Builder 上每类扩展点都是**有序列表**：`blockParserFactories`、`inlineContentParserFactories`、`delimiterProcessors`、`linkProcessors`、`postProcessors` 按注册顺序追加。文件: `commonmark/src/main/java/org/commonmark/parser/Parser.java:144`
- 默认值在 Builder 字段初始化时确定：`enabledBlockTypes` = 全部 7 种核心块类型、`includeSourceSpans = NONE`、`maxOpenBlockParsers = 100`、`maxInlineNesting = 100`。文件: `commonmark/src/main/java/org/commonmark/parser/Parser.java:151`
- `Parser` 构造时把自定义块工厂与 enabledBlockTypes 选中的核心工厂合成最终工厂列表，并**立即试构造一个 inline parser** 以便尽早暴露配置错误。文件: `commonmark/src/main/java/org/commonmark/parser/Parser.java:42`
- 若未显式设置 `inlineParserFactory`，默认用 `InlineParserImpl::new`。文件: `commonmark/src/main/java/org/commonmark/parser/Parser.java:399`

## 2. DocumentParser：块解析阶段

`Parser.parse(String)` 与 `parseReader(Reader)` 都只是：新建 `DocumentParser` → 逐行喂入 → `finalizeAndProcess` → 然后才跑 post processor。文件: `commonmark/src/main/java/org/commonmark/parser/Parser.java:86`、`commonmark/src/main/java/org/commonmark/parser/Parser.java:115`

- 核心块类型固定 7 种（BlockQuote、Heading、FencedCodeBlock、HtmlBlock、ThematicBreak、ListBlock、IndentedCodeBlock），`enabledBlockTypes` 只能在这个集合里做减法，传集合外的类型直接 `IllegalArgumentException`。文件: `commonmark/src/main/java/org/commonmark/internal/DocumentParser.java:21`、`commonmark/src/main/java/org/commonmark/internal/DocumentParser.java:120`
- **覆盖规则（块）**：`calculateBlockParserFactories` 把自定义工厂排在核心工厂之前，因此扩展可以抢占核心语法（如 yaml-front-matter 抢占 `---`）。同批自定义工厂之间按注册顺序，`findBlockStart` 取第一个返回非空的工厂。文件: `commonmark/src/main/java/org/commonmark/internal/DocumentParser.java:108`、`commonmark/src/main/java/org/commonmark/internal/DocumentParser.java:489`
- 逐行状态机：`parseLine` 先让已打开的块 `tryContinue`，再在最后一个匹配的容器上循环 `findBlockStart` 尝试新块起点，剩余内容作为文本行 `addLine`。文件: `commonmark/src/main/java/org/commonmark/internal/DocumentParser.java:210`
- 所有块关闭后 `processInlines()` 用同一个 `InlineParser` 实例按块关闭顺序逐个 `parseInlines`。文件: `commonmark/src/main/java/org/commonmark/internal/DocumentParser.java:507`、`commonmark/src/main/java/org/commonmark/internal/DocumentParser.java:564`
- `maxOpenBlockParsers` 的边界在 `findBlockStart` 入口：已打开块解析器数量超过上限时直接返回 `null`，新块起点退化为纯文本；Document 根解析器不计数（构造时即激活，见下）。文件: `commonmark/src/main/java/org/commonmark/internal/DocumentParser.java:490`、`commonmark/src/main/java/org/commonmark/internal/DocumentParser.java:100`

## 3. Inline 解析阶段

- **覆盖规则（inline content）**：自定义 `InlineContentParserFactory` 排在 5 个内置工厂（反斜杠转义、反引号、实体、autolink、行内 HTML）之前；同一触发字符下按列表顺序逐个 `tryParse`，第一个返回非 `none` 的获胜。文件: `commonmark/src/main/java/org/commonmark/internal/InlineParserImpl.java:68`
- **覆盖规则（link processor）**：自定义 `LinkProcessor` 排在 `CoreLinkProcessor` 之前，可覆盖内置链接解析。文件: `commonmark/src/main/java/org/commonmark/internal/InlineParserImpl.java:79`
- **覆盖规则（delimiter）**：内置 `*`、`_` 先注册，自定义 processor 后注册；同字符同开闭的多个 processor 被包成 `StaggeredDelimiterProcessor`，最短匹配长度优先；同字符同最小长度属非法配置。文件: `commonmark/src/main/java/org/commonmark/internal/InlineParserImpl.java:87`
- `maxInlineNesting` 的边界有两处：链接/图片在 `parseCloseBracket` 里检查 `opener.contentNestingDepth >= maxInlineNesting` 则放弃成链（括号按纯文本处理）；强调类分隔符在 `processDelimiters` 匹配时检查内容深度，超限则不再配对。注意 javadoc 自述该限制针对**结果节点的嵌套深度**，与由哪种语法产生无关，且树最多超限一层。文件: `commonmark/src/main/java/org/commonmark/internal/InlineParserImpl.java:472`、`commonmark/src/main/java/org/commonmark/internal/InlineParserImpl.java:845`

## 4. postProcess 阶段

- 块与 inline 全部解析完后，`Parser.postProcess` 按注册顺序依次调用每个 `PostProcessor`，**上一个的返回值作为下一个的输入**，最终返回值就是 `parse` 的结果。文件: `commonmark/src/main/java/org/commonmark/parser/Parser.java:135`
- `PostProcessor.process` 的契约只有一句"返回后处理结果，可以是修改后的入参"——允许返回**完全不同的根节点**，库不做任何校验。文件: `commonmark/src/main/java/org/commonmark/parser/PostProcessor.java:9`

## 5. HtmlRenderer：NodeRendererMap 与 AttributeProvider

- `HtmlRenderer` 构造时把 `CoreHtmlNodeRenderer` 的工厂**追加到用户工厂之后**，这是"用户可覆盖核心节点渲染"的机制来源。文件: `commonmark/src/main/java/org/commonmark/renderer/html/HtmlRenderer.java:40`
- **覆盖规则（renderer）**：`NodeRendererMap.add` 对每个节点类型 `putIfAbsent`——**先注册的工厂获胜**，后注册的同类型 renderer 被静默丢弃。这是 javadoc 明确承诺的行为。文件: `commonmark/src/main/java/org/commonmark/internal/renderer/NodeRendererMap.java:19`、`commonmark/src/main/java/org/commonmark/renderer/html/HtmlRenderer.java:199`
- 每次 `render` 调用新建 `RendererContext`：先按顺序实例化所有 `AttributeProvider`，再按顺序实例化所有 `NodeRenderer` 填入 map。文件: `commonmark/src/main/java/org/commonmark/renderer/html/HtmlRenderer.java:239`
- 核心 renderer 每写一个标签前调 `context.extendAttributes`，后者按注册顺序把属性 map 依次交给每个 `AttributeProvider.setAttributes` 修改——**多个 provider 是叠加而非覆盖关系**，后注册的可以看到并改坏先注册的结果；同一节点渲染多个嵌套标签时会被调用多次。文件: `commonmark/src/main/java/org/commonmark/renderer/html/CoreHtmlNodeRenderer.java:302`、`commonmark/src/main/java/org/commonmark/renderer/html/HtmlRenderer.java:283`、`commonmark/src/main/java/org/commonmark/renderer/html/HtmlRenderer.java:313`
- Markdown / text renderer 走同样的"访问节点"模型（各自的 `NodeRendererMap` 与核心 renderer 兜底），本文不展开。文件: `commonmark/src/main/java/org/commonmark/renderer/NodeRenderer.java:7`

## 6. 四个边界参数的精确语义

- **enabledBlockTypes**：只能在 7 种核心类型中取子集；空集表示禁用全部核心块语法（自定义块工厂不受影响，仍排在前面）。文件: `commonmark/src/main/java/org/commonmark/parser/Parser.java:209`、`commonmark/src/main/java/org/commonmark/internal/DocumentParser.java:108`
- **maxOpenBlockParsers**：默认 100，必须 ≥0，`Integer.MAX_VALUE` 表示不限；超限后新块起点当纯文本，根 Document 不计入。文件: `commonmark/src/main/java/org/commonmark/parser/Parser.java:243`、`commonmark/src/main/java/org/commonmark/internal/DocumentParser.java:490`
- **maxInlineNesting**：默认 100，必须 ≥0；同时约束强调类分隔符与图片/链接产生的嵌套，超限部分按纯文本处理；结果树最多超限一层（实现自述）。文件: `commonmark/src/main/java/org/commonmark/parser/Parser.java:278`、`commonmark/src/main/java/org/commonmark/internal/InlineParserImpl.java:467`
- **IncludeSourceSpans**：三档枚举 `NONE / BLOCKS / BLOCKS_AND_INLINES`，默认 `NONE`。文件: `commonmark/src/main/java/org/commonmark/parser/IncludeSourceSpans.java:9`
  - 行级 span 在 `setLine` 里只要非 `NONE` 就记录（含 inputIndex）。文件: `commonmark/src/main/java/org/commonmark/internal/DocumentParser.java:360`
  - 块 span 由 `addSourceSpans` 累计，**故意跳过 Document 自身**（从索引 1 开始），所以 Document 的 span 列表恒为空——取证输出中 `Document []` 即此原因。文件: `commonmark/src/main/java/org/commonmark/internal/DocumentParser.java:471`
  - inline span 只在 `BLOCKS_AND_INLINES` 下随 `addLine` 记录；部分消费的 tab 会导致 span 长度与内容长度不一致（代码注释自述）。文件: `commonmark/src/main/java/org/commonmark/internal/DocumentParser.java:461`

## 7. 三个独立风险与复现

### 风险 1：post processor 替换根节点后，source span 不再可信

`postProcess` 允许返回全新根节点且不做任何校验（文件: `commonmark/src/main/java/org/commonmark/parser/Parser.java:135`）。span 是在块/inline 解析期间一次性算好的，库**不会**在后处理后再重算；新创建的节点默认没有任何 span，被搬动的节点仍指向原文偏移。对比官方扩展可知维护 span 完全是 post processor 自己的责任：`AutolinkPostProcessor` 手工用 `subSpan` 给新 Text/Link 节点重建 span。文件: `commonmark-ext-autolink/src/main/java/org/commonmark/ext/autolink/internal/AutolinkPostProcessor.java:54`

复现：注册一个 `postProcessor(node -> new Document())`，`parse` 正常返回但整棵树 span 为空、原 AST 被静默丢弃；`UnderstandingProofIntegrationTest` 中的 `RecordingPostProcessor` 证明 post processor 拿到的确实是解析完成的 `Document` 根（`assertSame(document, postProcessor.seenRoot)`），替换它不会有任何告警。

结论：**公开保证只到"post processor 可以换根"为止；换根/改树后 span 的准确性是实现细节，由扩展作者自行维护。**

### 风险 2：renderer factory 注册顺序改变覆盖结果

`NodeRendererMap` 是 `putIfAbsent`，先注册者获胜（文件: `commonmark/src/main/java/org/commonmark/internal/renderer/NodeRendererMap.java:23`），核心 renderer 永远垫底（文件: `commonmark/src/main/java/org/commonmark/renderer/html/HtmlRenderer.java:43`）。因此两个扩展（或同一扩展与一次手动 `nodeRendererFactory` 调用）声明同一节点类型时，**交换注册顺序就交换渲染结果**，且失败方无任何报错。

复现：`UnderstandingProofIntegrationTest.rendererRecordsAttributesAndFirstRegisteredFactoryWins` 在扩展之后再注册一个输出 `mention-loser` 的 `Mention` renderer，断言 HTML 含 `class="mention"` 且不含 `mention-loser`；把两行注册顺序对调，断言立即反转。

结论："先注册获胜"是 javadoc 承诺的公开行为（文件: `commonmark/src/main/java/org/commonmark/renderer/html/HtmlRenderer.java:199`），但**跨扩展的相对顺序取决于调用方组装 `extensions(...)` 的顺序**，这是集成方最容易踩的坑。

### 风险 3：Reader 入口遇 BOM 或分块边界时与 String 入口的位置差异

- **BOM**：两个入口都**不**剥 BOM。`\uFEFF# Hi\n` 中 BOM 成为段落文本、标题不再被识别，这是回归测试固化的行为；`parseReader` 的 javadoc 明确要求调用方自行跳过 BOM。文件: `commonmark/src/test/java/org/commonmark/test/RegressionTest.java:61`、`commonmark/src/main/java/org/commonmark/parser/Parser.java:105`
- **分块边界**：Reader 入口走 `LineReader`（8192 字符缓冲），CR 落在缓冲末尾时置 `cr` 标志延迟一行判定，`\r\n` 被跨块正确拼合；`inputIndex` 由"行内容长度 + 行终止符长度"累计。文件: `commonmark/src/main/java/org/commonmark/internal/util/LineReader.java:19`、`commonmark/src/main/java/org/commonmark/internal/util/LineReader.java:83`、`commonmark/src/main/java/org/commonmark/internal/DocumentParser.java:155`
- String 入口用 `findLineBreak` 切分并以 `lineStart` 偏移计数。文件: `commonmark/src/main/java/org/commonmark/internal/DocumentParser.java:133`

复现：`UnderstandingProofIntegrationTest.readerAndStringEntriesAgreeOnBomAndChunkBoundaries` 用每 4096 字符喂一次的 `ChunkedReader` 解析"9000 字符长行 + 跨缓冲 `\r\n` + tail"，断言与 String 入口的节点顺序、全部 span、HTML 完全一致，且 `tail` 的 `inputIndex` 精确等于 9006（= 4 + 9000 + 2）。BOM 输入则两入口一致地渲染为 `<p>\uFEFF# Hi</p>`。

结论：**两入口产出一致是当前实现细节层面的强保证**（行切分逻辑各自独立，属两份代码）；BOM 不剥离是公开承诺。若 Reader 实现返回 0 长度 read，`LineReader.fill` 会死循环（do-while 等非零），这也是未文档化的实现细节。文件: `commonmark/src/main/java/org/commonmark/internal/util/LineReader.java:127`

## 8. 公开保证 vs 实现细节速查

| 主题 | 公开文档保证 | 当前实现细节（可能变化） |
| --- | --- | --- |
| 块工厂顺序 | 自定义先于内置（`customBlockParserFactory` javadoc） | 同批自定义按注册序、首个非空 BlockStart 获胜 |
| inline 解析顺序 | 自定义 content parser / link processor 先于内置 | 5 个内置工厂的具体清单与顺序 |
| renderer 覆盖 | 同类型先注册获胜、核心垫底（javadoc 明示） | `putIfAbsent` + `HashMap`，失败方静默 |
| attribute provider | 可增删改属性、同一节点可能调用多次 | 多 provider 严格按注册序叠加于同一 `LinkedHashMap` |
| source span | 默认关闭；`BLOCKS_AND_INLINES` 才含 inline | Document 自身永不带 span；tab 部分消费时 span 长度与内容不符 |
| BOM | 调用方负责剥离（javadoc + 回归测试） | 两入口行切分实现独立但结果一致 |
| post processor | 可修改或替换返回节点 | 替换根后 span 不重算、不校验 |

## 9. 复现命令

```bash
./mvnw -q -DskipTests package
./mvnw -q -pl commonmark-integration-test -Dtest='*UnderstandingProof*' test
./mvnw -q test
test -s ANALYSIS.md && grep -c '文件:' ANALYSIS.md
```
