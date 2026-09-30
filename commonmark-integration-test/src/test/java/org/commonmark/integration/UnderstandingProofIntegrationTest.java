package org.commonmark.integration;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.commonmark.Extension;
import org.commonmark.node.*;
import org.commonmark.parser.IncludeSourceSpans;
import org.commonmark.parser.Parser;
import org.commonmark.parser.PostProcessor;
import org.commonmark.parser.beta.*;
import org.commonmark.renderer.NodeRenderer;
import org.commonmark.renderer.html.*;
import org.junit.jupiter.api.Test;

/**
 * Forensic test for ANALYSIS.md: instruments the pipeline with a minimal custom extension and
 * records (a) the node order and source spans produced by the parser, (b) HTML attribute provider
 * calls, (c) node renderer precedence, and (d) String vs Reader entry differences. It observes
 * behavior through public extension points only; it never infers the call chain from the final
 * rendered string.
 */
public class UnderstandingProofIntegrationTest {

    private static final String INPUT =
            "# Proof Heading\n"
                    + "\n"
                    + "- item one\n"
                    + "  - nested with [a link](https://example.com)\n"
                    + "- item two mentions @alice\n";

    /** Custom inline node parsed from {@code @name} by the extension below. */
    static class Mention extends CustomNode {
        private final String literal;

        Mention(String literal) {
            this.literal = literal;
        }

        String getLiteral() {
            return literal;
        }
    }

    static class MentionInlineParser implements InlineContentParser {
        @Override
        public ParsedInline tryParse(InlineParserState state) {
            Scanner scanner = state.scanner();
            scanner.next(); // consume '@'
            Position start = scanner.position();
            while (Character.isLetterOrDigit(scanner.peek())) {
                scanner.next();
            }
            Position end = scanner.position();
            if (start.equals(end)) {
                return ParsedInline.none();
            }
            String name = scanner.getSource(start, end).getContent();
            return ParsedInline.of(new Mention(name), end);
        }
    }

    static class MentionInlineParserFactory implements InlineContentParserFactory {
        @Override
        public Set<Character> getTriggerCharacters() {
            return Set.of('@');
        }

        @Override
        public InlineContentParser create() {
            return new MentionInlineParser();
        }
    }

    /** Records the root node it receives and returns it unchanged. */
    static class RecordingPostProcessor implements PostProcessor {
        Node seenRoot;

        @Override
        public Node process(Node node) {
            seenRoot = node;
            return node;
        }
    }

    /** Records every invocation and tags Link nodes with a proof attribute. */
    static class RecordingAttributeProvider implements AttributeProvider {
        final List<String> calls = new ArrayList<>();

        @Override
        public void setAttributes(Node node, String tagName, Map<String, String> attributes) {
            calls.add(node.getClass().getSimpleName() + " <" + tagName + "> " + attributes);
            if (node instanceof Link) {
                attributes.put("data-proof", "link");
            }
        }
    }

    static class MentionNodeRenderer implements NodeRenderer {
        private final HtmlNodeRendererContext context;
        private final String cssClass;

        MentionNodeRenderer(HtmlNodeRendererContext context, String cssClass) {
            this.context = context;
            this.cssClass = cssClass;
        }

        @Override
        public Set<Class<? extends Node>> getNodeTypes() {
            return Set.of(Mention.class);
        }

        @Override
        public void render(Node node) {
            Mention mention = (Mention) node;
            HtmlWriter writer = context.getWriter();
            writer.tag("span", context.extendAttributes(node, "span", Map.of("class", cssClass)));
            writer.text("@" + mention.getLiteral());
            writer.tag("/span");
        }
    }

    /** Minimal extension touching parser, post processor, attribute provider and node renderer. */
    static class ProofExtension
            implements Parser.ParserExtension, HtmlRenderer.HtmlRendererExtension {
        final RecordingPostProcessor postProcessor = new RecordingPostProcessor();
        final RecordingAttributeProvider attributeProvider = new RecordingAttributeProvider();

        @Override
        public void extend(Parser.Builder parserBuilder) {
            parserBuilder.customInlineContentParserFactory(new MentionInlineParserFactory());
            parserBuilder.postProcessor(postProcessor);
        }

        @Override
        public void extend(HtmlRenderer.Builder rendererBuilder) {
            rendererBuilder.nodeRendererFactory(
                    context -> new MentionNodeRenderer(context, "mention"));
            rendererBuilder.attributeProviderFactory(context -> attributeProvider);
        }
    }

    private static List<String> collectNodeOrderAndSpans(Node root) {
        List<String> lines = new ArrayList<>();
        root.accept(
                new AbstractVisitor() {
                    @Override
                    protected void visitChildren(Node parent) {
                        lines.add(parent.getClass().getSimpleName() + " " + parent.getSourceSpans());
                        super.visitChildren(parent);
                    }
                });
        return lines;
    }

    @Test
    public void parserProducesOrderedNodesWithSpansAndPostProcessorSeesRoot() {
        ProofExtension extension = new ProofExtension();
        Parser parser =
                Parser.builder()
                        .extensions(List.<Extension>of(extension))
                        .includeSourceSpans(IncludeSourceSpans.BLOCKS_AND_INLINES)
                        .build();

        Node document = parser.parse(INPUT);

        List<String> trace = collectNodeOrderAndSpans(document);
        System.out.println("=== node order + source spans (pre-order) ===");
        trace.forEach(System.out::println);

        // Post processor ran after block+inline parsing and saw the Document root.
        assertSame(document, extension.postProcessor.seenRoot);
        assertInstanceOf(Document.class, extension.postProcessor.seenRoot);

        // Pre-order node sequence: heading first, then the nested list, link and custom Mention.
        List<String> types =
                trace.stream().map(line -> line.substring(0, line.indexOf(' '))).toList();
        assertEquals(
                List.of(
                        "Document", "Heading", "Text", "BulletList", "ListItem", "Paragraph",
                        "Text", "BulletList", "ListItem", "Paragraph", "Text", "Link", "Text",
                        "ListItem", "Paragraph", "Text", "Mention"),
                types);

        // Heading sits on line 0, the nested link on line 3, the mention on line 4.
        Heading heading = (Heading) document.getFirstChild();
        assertEquals(0, heading.getSourceSpans().get(0).getLineIndex());
        Link link = findFirst(document, Link.class);
        assertEquals(3, link.getSourceSpans().get(0).getLineIndex());
        Mention mention = findFirst(document, Mention.class);
        assertEquals("alice", mention.getLiteral());
        assertEquals(4, mention.getSourceSpans().get(0).getLineIndex());
    }

    @Test
    public void rendererRecordsAttributesAndFirstRegisteredFactoryWins() {
        ProofExtension extension = new ProofExtension();
        Parser parser =
                Parser.builder().extensions(List.<Extension>of(extension)).build();
        Node document = parser.parse(INPUT);

        HtmlRenderer renderer =
                HtmlRenderer.builder()
                        .extensions(List.<Extension>of(extension))
                        // Second factory for the same node type: must lose to the extension's.
                        .nodeRendererFactory(
                                context -> new MentionNodeRenderer(context, "mention-loser"))
                        .build();
        String html = renderer.render(document);

        System.out.println("=== attribute provider calls ===");
        extension.attributeProvider.calls.forEach(System.out::println);
        System.out.println("=== html ===");
        System.out.println(html);

        // First registered factory wins (NodeRendererMap keeps the first mapping).
        assertTrue(html.contains("class=\"mention\""), html);
        assertFalse(html.contains("mention-loser"), html);

        // Attribute provider was consulted for the link tag and added our attribute.
        assertTrue(html.contains("<a href=\"https://example.com\" data-proof=\"link\">"), html);
        assertTrue(
                extension.attributeProvider.calls.stream()
                        .anyMatch(call -> call.startsWith("Link <a>")),
                extension.attributeProvider.calls.toString());
    }

    @Test
    public void readerAndStringEntriesAgreeOnBomAndChunkBoundaries() throws IOException {
        Parser parser =
                Parser.builder()
                        .includeSourceSpans(IncludeSourceSpans.BLOCKS_AND_INLINES)
                        .build();
        HtmlRenderer renderer = HtmlRenderer.builder().build();

        // BOM is NOT stripped by either entry point: it becomes literal text and the heading
        // is not recognized (documented behavior, see Parser.parseReader javadoc).
        String bom = "\uFEFF# Hi\n";
        Node fromString = parser.parse(bom);
        Node fromReader = parser.parseReader(new StringReader(bom));
        assertEquals(
                renderer.render(fromString),
                renderer.render(fromReader));
        assertEquals("<p>\uFEFF# Hi</p>\n", renderer.render(fromString));
        System.out.println("=== BOM input renders as ===");
        System.out.print(renderer.render(fromString));

        // A CR/LF pair split across the 8192-char read buffer must yield identical lines,
        // positions and spans as the String entry point.
        String longLine = "a".repeat(9000);
        String chunked = "# T\n" + longLine + "\r\n" + "tail\n";
        Node stringAst = parser.parse(chunked);
        Node readerAst = parser.parseReader(new ChunkedReader(chunked, 4096));

        assertEquals(
                collectNodeOrderAndSpans(stringAst),
                collectNodeOrderAndSpans(readerAst));
        assertEquals(renderer.render(stringAst), renderer.render(readerAst));

        Text tail = findText(readerAst, "tail");
        // inputIndex of "tail" = 4 ("# T\n") + 9000 + 2 ("\r\n")
        assertEquals(9006, tail.getSourceSpans().get(0).getInputIndex());
        System.out.println("=== tail paragraph span via chunked reader ===");
        System.out.println(tail.getSourceSpans());
    }

    private static Text findText(Node root, String literal) {
        List<Text> found = new ArrayList<>();
        root.accept(
                new AbstractVisitor() {
                    @Override
                    public void visit(Text text) {
                        if (text.getLiteral().equals(literal)) {
                            found.add(text);
                        }
                    }
                });
        assertFalse(found.isEmpty(), "no Text node with literal " + literal);
        return found.get(0);
    }

    private static <T extends Node> T findFirst(Node root, Class<T> type) {
        List<T> found = new ArrayList<>();
        root.accept(
                new AbstractVisitor() {
                    @Override
                    protected void visitChildren(Node parent) {
                        if (type.isInstance(parent)) {
                            found.add(type.cast(parent));
                        }
                        super.visitChildren(parent);
                    }
                });
        assertFalse(found.isEmpty(), "no node of type " + type);
        return found.get(0);
    }

    /** Reader that returns at most {@code chunkSize} chars per read to force buffer refills. */
    private static class ChunkedReader extends Reader {
        private final String content;
        private final int chunkSize;
        private int pos;

        ChunkedReader(String content, int chunkSize) {
            this.content = content;
            this.chunkSize = chunkSize;
        }

        @Override
        public int read(char[] cbuf, int off, int len) {
            if (pos >= content.length()) {
                return -1;
            }
            int n = Math.min(Math.min(len, chunkSize), content.length() - pos);
            content.getChars(pos, pos + n, cbuf, off);
            pos += n;
            return n;
        }

        @Override
        public void close() {}
    }
}
