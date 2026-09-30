package org.commonmark.integration.proof;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.FilterReader;
import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.commonmark.Extension;
import org.commonmark.node.CustomNode;
import org.commonmark.node.Document;
import org.commonmark.node.Node;
import org.commonmark.node.SourceSpan;
import org.commonmark.parser.IncludeSourceSpans;
import org.commonmark.parser.Parser;
import org.commonmark.parser.PostProcessor;
import org.commonmark.parser.beta.InlineContentParser;
import org.commonmark.parser.beta.InlineContentParserFactory;
import org.commonmark.parser.beta.InlineParserState;
import org.commonmark.parser.beta.ParsedInline;
import org.commonmark.parser.beta.Position;
import org.commonmark.renderer.NodeRenderer;
import org.commonmark.renderer.html.AttributeProvider;
import org.commonmark.renderer.html.AttributeProviderContext;
import org.commonmark.renderer.html.AttributeProviderFactory;
import org.commonmark.renderer.html.HtmlNodeRendererContext;
import org.commonmark.renderer.html.HtmlNodeRendererFactory;
import org.commonmark.renderer.html.HtmlRenderer;
import org.commonmark.renderer.html.HtmlWriter;
import org.junit.jupiter.api.Test;

/**
 * Forensic-only integration test for the extension ordering / override analysis.
 *
 * <p>The assertions and the printed evidence are collected from the AST, from an
 * {@link AttributeProvider} and from a custom {@link NodeRenderer} directly. Nothing is inferred
 * backwards from the final rendered string.
 */
class UnderstandingProofIntegrationTest {

    /** Sample input: heading, nested list, link and the custom {@code ==mark==} syntax. */
    private static final String INPUT =
            "# Analysis\n\n"
                    + "- first item with a [link](https://example.com)\n"
                    + "  - nested item with ==custom mark== here\n";

    // -- Minimal custom extension ---------------------------------------------------------------

    static class Mark extends CustomNode {}

    static class ProofExtension
            implements Parser.ParserExtension, HtmlRenderer.HtmlRendererExtension {

        final List<String> evidence = new ArrayList<>();

        @Override
        public void extend(Parser.Builder parserBuilder) {
            parserBuilder.customInlineContentParserFactory(new MarkInlineParser.Factory());
        }

        @Override
        public void extend(HtmlRenderer.Builder rendererBuilder) {
            rendererBuilder.attributeProviderFactory(new MarkAttributeProvider.Factory(evidence));
            rendererBuilder.nodeRendererFactory(new MarkNodeRenderer.Factory(evidence, "mark"));
        }
    }

    static class MarkInlineParser implements InlineContentParser {

        @Override
        public ParsedInline tryParse(InlineParserState state) {
            var scanner = state.scanner();
            var start = scanner.position();
            if (scanner.matchMultiple('=') != 2) {
                // A single "=" or a longer run is not the custom syntax; reset and let text
                // handling take over.
                scanner.setPosition(start);
                return null;
            }
            var contentStart = scanner.position();
            if (scanner.find('=') <= 0) {
                scanner.setPosition(start);
                return null;
            }
            // find('=') stopped with the scanner on the first closing '='.
            var contentEnd = scanner.position();
            if (scanner.matchMultiple('=') != 2) {
                scanner.setPosition(start);
                return null;
            }
            var node = new Mark();
            var content = scanner.getSource(contentStart, contentEnd).getContent();
            node.appendChild(new org.commonmark.node.Text(content));
            return ParsedInline.of(node, scanner.position());
        }

        static class Factory implements InlineContentParserFactory {
            @Override
            public Set<Character> getTriggerCharacters() {
                return Set.of('=');
            }

            @Override
            public InlineContentParser create() {
                return new MarkInlineParser();
            }
        }
    }

    static class MarkAttributeProvider implements AttributeProvider {

        private final List<String> evidence;

        MarkAttributeProvider(List<String> evidence) {
            this.evidence = evidence;
        }

        @Override
        public void setAttributes(Node node, String tagName, Map<String, String> attributes) {
            if (node instanceof Mark) {
                attributes.put("data-proof", "attr");
                evidence.add(
                        "attribute: tag="
                                + tagName
                                + ", data-proof="
                                + attributes.get("data-proof"));
            }
        }

        static class Factory implements AttributeProviderFactory {
            private final List<String> evidence;

            Factory(List<String> evidence) {
                this.evidence = evidence;
            }

            @Override
            public AttributeProvider create(AttributeProviderContext context) {
                return new MarkAttributeProvider(evidence);
            }
        }
    }

    static class MarkNodeRenderer implements NodeRenderer {

        private final List<String> evidence;
        private final String tag;
        private final HtmlNodeRendererContext context;
        private final HtmlWriter html;

        MarkNodeRenderer(HtmlNodeRendererContext context, List<String> evidence, String tag) {
            this.context = context;
            this.evidence = evidence;
            this.tag = tag;
            this.html = context.getWriter();
        }

        @Override
        public Set<Class<? extends Node>> getNodeTypes() {
            return Set.of(Mark.class);
        }

        @Override
        public void render(Node node) {
            evidence.add("renderer: winner=" + tag);
            Map<String, String> attributes = context.extendAttributes(node, "mark", Map.of());
            html.tag(tag, attributes);
            renderChildren(node);
            html.tag("/" + tag);
        }

        private void renderChildren(Node parent) {
            Node node = parent.getFirstChild();
            while (node != null) {
                Node next = node.getNext();
                context.render(node);
                node = next;
            }
        }

        static class Factory implements HtmlNodeRendererFactory {
            private final List<String> evidence;
            private final String tag;

            Factory(List<String> evidence, String tag) {
                this.evidence = evidence;
                this.tag = tag;
            }

            @Override
            public NodeRenderer create(HtmlNodeRendererContext context) {
                return new MarkNodeRenderer(context, evidence, tag);
            }
        }
    }

    // -- Helpers --------------------------------------------------------------------------------

    private static List<String> describe(Node root) {
        List<String> lines = new ArrayList<>();
        describeInto(root, 0, lines);
        return lines;
    }

    private static void describeInto(Node node, int depth, List<String> lines) {
        var spans = node.getSourceSpans();
        String spanText;
        if (spans.isEmpty()) {
            spanText = "no-span";
        } else {
            List<String> parts = new ArrayList<>();
            for (SourceSpan span : spans) {
                parts.add(
                        "L"
                                + span.getLineIndex()
                                + ":C"
                                + span.getColumnIndex()
                                + ":I"
                                + span.getInputIndex()
                                + "+"
                                + span.getLength());
            }
            spanText = String.join(",", parts);
        }
        lines.add(
                "node: depth="
                        + depth
                        + " type="
                        + node.getClass().getSimpleName()
                        + " spans="
                        + spanText);
        for (var child = node.getFirstChild(); child != null; child = child.getNext()) {
            describeInto(child, depth + 1, lines);
        }
    }

    private static Parser parserWith(Extension extension) {
        return Parser.builder()
                .extensions(List.of(extension))
                .includeSourceSpans(IncludeSourceSpans.BLOCKS_AND_INLINES)
                .build();
    }

    // -- Test 1: node order, spans and attributes come from the AST / callbacks, not the output --

    @Test
    void printsNodeOrderSpansAndAttributes() {
        var extension = new ProofExtension();
        Node document = parserWith(extension).parse(INPUT);

        System.out.println("== proof: node order and spans ==");
        List<String> tree = describe(document);
        tree.forEach(System.out::println);

        HtmlRenderer renderer =
                HtmlRenderer.builder().extensions(List.of(extension)).build();
        // Render so that the attribute provider and the custom node renderer actually run; the
        // assertions below do not inspect this string.
        String ignored = renderer.render(document);
        System.out.println("== proof: renderer / attribute callbacks ==");
        extension.evidence.forEach(System.out::println);

        assertThat(tree)
                .anyMatch(line -> line.contains("type=Mark ") || line.contains("type=Mark spans"));
        assertThat(tree).anyMatch(line -> line.contains("type=Text") && !line.contains("no-span"));
        assertThat(extension.evidence).contains("renderer: winner=mark");
        assertThat(extension.evidence)
                .contains("attribute: tag=mark, data-proof=attr");
        assertThat(ignored).contains("<mark").contains("</mark>");
    }

    // -- Risk A: replacing the root node in a post processor orphaned the recorded spans ---------

    static class RootReplacingPostProcessor implements PostProcessor {
        @Override
        public Node process(Node node) {
            Document replacement = new Document();
            // Deliberately copy no source spans from the original tree.
            var paragraph = new org.commonmark.node.Paragraph();
            paragraph.appendChild(new org.commonmark.node.Text("synthesized"));
            replacement.appendChild(paragraph);
            return replacement;
        }
    }

    @Test
    void postProcessorReplacingRootProducesUntrustworthySpans() {
        Parser parser =
                Parser.builder()
                        .postProcessor(new RootReplacingPostProcessor())
                        .includeSourceSpans(IncludeSourceSpans.BLOCKS_AND_INLINES)
                        .build();
        Node replaced = parser.parse(INPUT);

        List<String> tree = describe(replaced);
        System.out.println("== risk A: spans after root replacement ==");
        tree.forEach(System.out::println);

        assertThat(replaced).isNotSameAs(parser.parse(INPUT));
        assertThat(replaced.getSourceSpans()).isEmpty();
        assertThat(replaced.getFirstChild().getSourceSpans()).isEmpty();
        assertThat(replaced.getFirstChild().getFirstChild().getSourceSpans()).isEmpty();
    }

    // -- Risk B: renderer factory registration order decides which override wins ----------------

    @Test
    void firstNodeRendererFactoryForNodeTypeWins() {
        var evidence = new ArrayList<String>();
        var first = new MarkNodeRenderer.Factory(evidence, "first");
        var second = new MarkNodeRenderer.Factory(evidence, "second");

        var orderAB = HtmlRenderer.builder().nodeRendererFactory(first).nodeRendererFactory(second)
                .build();
        orderAB.render(parserWith(new ProofExtension()).parse(INPUT));
        System.out.println("== risk B: order [first, second] ==");
        evidence.forEach(System.out::println);
        assertThat(evidence).contains("renderer: winner=first");
        assertThat(evidence).doesNotContain("renderer: winner=second");

        evidence.clear();
        var orderBA = HtmlRenderer.builder().nodeRendererFactory(second).nodeRendererFactory(first)
                .build();
        orderBA.render(parserWith(new ProofExtension()).parse(INPUT));
        System.out.println("== risk B: order [second, first] ==");
        evidence.forEach(System.out::println);
        assertThat(evidence).contains("renderer: winner=second");
        assertThat(evidence).doesNotContain("renderer: winner=first");
    }

    // -- Risk C: Reader vs String entry points, BOM and chunk boundaries -------------------------

    /** Reader that hands out the underlying characters in fixed-size chunks. */
    static class ChunkedReader extends FilterReader {
        ChunkedReader(String input) {
            super(new StringReader(input));
        }

        @Override
        public int read(char[] cbuf, int off, int len) throws IOException {
            return super.read(cbuf, off, Math.min(len, 2));
        }
    }

    @Test
    void readerAndStringEntryPointsAgreeExceptForBomHandling() throws IOException {
        String plain = "# a\r\n# b";
        Node fromString =
                Parser.builder()
                        .includeSourceSpans(IncludeSourceSpans.BLOCKS_AND_INLINES)
                        .build()
                        .parse(plain);
        Node fromReader =
                Parser.builder()
                        .includeSourceSpans(IncludeSourceSpans.BLOCKS_AND_INLINES)
                        .build()
                        .parseReader(new ChunkedReader(plain));

        List<String> stringTree = describe(fromString);
        List<String> readerTree = describe(fromReader);
        System.out.println("== risk C: String entry ==");
        stringTree.forEach(System.out::println);
        System.out.println("== risk C: Reader entry (2-char chunks, CRLF straddles a boundary) ==");
        readerTree.forEach(System.out::println);

        // Spans, including inputIndex, are identical even though the Reader saw the CR and LF in
        // separate chunks.
        assertThat(readerTree).isEqualTo(stringTree);

        String withBom = "\uFEFF# heading";
        Node bomString =
                Parser.builder()
                        .includeSourceSpans(IncludeSourceSpans.BLOCKS_AND_INLINES)
                        .build()
                        .parse(withBom);
        Node bomReader =
                Parser.builder()
                        .includeSourceSpans(IncludeSourceSpans.BLOCKS_AND_INLINES)
                        .build()
                        .parseReader(new ChunkedReader(withBom));

        List<String> bomStringTree = describe(bomString);
        List<String> bomReaderTree = describe(bomReader);
        System.out.println("== risk C: BOM, String entry ==");
        bomStringTree.forEach(System.out::println);
        System.out.println("== risk C: BOM, Reader entry (BOM split across chunks) ==");
        bomReaderTree.forEach(System.out::println);

        // Both entry points behave identically; neither strips the BOM, so it is counted as the
        // first column and the line is not recognized as a heading.
        assertThat(bomReaderTree).isEqualTo(bomStringTree);
        assertThat(bomString.getFirstChild())
                .isInstanceOf(org.commonmark.node.Paragraph.class);
        SourceSpan firstSpan = bomString.getFirstChild().getSourceSpans().get(0);
        assertThat(firstSpan.getInputIndex()).isZero();
        assertThat(firstSpan.getLength()).isEqualTo(withBom.length());
    }

}
