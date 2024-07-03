package org.commonmark.ext.footnotes;

import org.commonmark.Extension;
import org.commonmark.node.Document;
import org.commonmark.node.Paragraph;
import org.commonmark.node.Text;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.html.HtmlRenderer;
import org.commonmark.testutil.Asserts;
import org.commonmark.testutil.RenderingTestCase;
import org.junit.Test;

import java.util.Set;

public class FootnoteHtmlRendererTest extends RenderingTestCase {
    private static final Set<Extension> EXTENSIONS = Set.of(FootnotesExtension.create());
    private static final Parser PARSER = Parser.builder().extensions(EXTENSIONS).build();
    private static final HtmlRenderer RENDERER = HtmlRenderer.builder().extensions(EXTENSIONS).build();

    @Test
    public void testOne() {
        assertRendering("Test [^foo]\n\n[^foo]: note\n",
                "<p>Test <sup class=\"footnote-ref\"><a href=\"#fn-foo\" id=\"fnref-foo\" data-footnote-ref>1</a></sup></p>\n" +
                        "<section class=\"footnotes\" data-footnotes>\n" +
                        "<ol>\n" +
                        "<li id=\"fn-foo\">\n" +
                        "<p>note <a href=\"#fnref-foo\" class=\"footnote-backref\" data-footnote-backref data-footnote-backref-idx=\"1\" aria-label=\"Back to reference 1\">↩</a></p>\n" +
                        "</li>\n" +
                        "</ol>\n" +
                        "</section>\n");
    }

    @Test
    public void testLabelNormalization() {
        // Labels match via their normalized form. For the href and IDs to match, rendering needs to use the
        // label from the definition consistently.
        assertRendering("Test [^bar]\n\n[^BAR]: note\n",
                "<p>Test <sup class=\"footnote-ref\"><a href=\"#fn-BAR\" id=\"fnref-BAR\" data-footnote-ref>1</a></sup></p>\n" +
                        "<section class=\"footnotes\" data-footnotes>\n" +
                        "<ol>\n" +
                        "<li id=\"fn-BAR\">\n" +
                        "<p>note <a href=\"#fnref-BAR\" class=\"footnote-backref\" data-footnote-backref data-footnote-backref-idx=\"1\" aria-label=\"Back to reference 1\">↩</a></p>\n" +
                        "</li>\n" +
                        "</ol>\n" +
                        "</section>\n");
    }

    @Test
    public void testMultipleReferences() {
        // Tests a few things:
        // - Numbering is based on the reference order, not the definition order
        // - The same number is used when a definition is referenced multiple times
        // - Multiple backrefs are rendered
        assertRendering("First [^foo]\n\nThen [^bar]\n\nThen [^foo] again\n\n[^bar]: b\n[^foo]: f\n",
                "<p>First <sup class=\"footnote-ref\"><a href=\"#fn-foo\" id=\"fnref-foo\" data-footnote-ref>1</a></sup></p>\n" +
                        "<p>Then <sup class=\"footnote-ref\"><a href=\"#fn-bar\" id=\"fnref-bar\" data-footnote-ref>2</a></sup></p>\n" +
                        "<p>Then <sup class=\"footnote-ref\"><a href=\"#fn-foo\" id=\"fnref-foo-2\" data-footnote-ref>1</a></sup> again</p>\n" +
                        "<section class=\"footnotes\" data-footnotes>\n" +
                        "<ol>\n" +
                        "<li id=\"fn-foo\">\n" +
                        "<p>f <a href=\"#fnref-foo\" class=\"footnote-backref\" data-footnote-backref data-footnote-backref-idx=\"1\" aria-label=\"Back to reference 1\">↩</a> <a href=\"#fnref-foo-2\" class=\"footnote-backref\" data-footnote-backref data-footnote-backref-idx=\"1-2\" aria-label=\"Back to reference 1-2\"><sup class=\"footnote-ref\">2</sup>↩</a></p>\n" +
                        "</li>\n" +
                        "<li id=\"fn-bar\">\n" +
                        "<p>b <a href=\"#fnref-bar\" class=\"footnote-backref\" data-footnote-backref data-footnote-backref-idx=\"2\" aria-label=\"Back to reference 2\">↩</a></p>\n" +
                        "</li>\n" +
                        "</ol>\n" +
                        "</section>\n");
    }

    @Test
    public void testDefinitionWithTwoParagraphs() {
        // With two paragraphs, the backref <a> should be added to the second one
        assertRendering("Test [^foo]\n\n[^foo]: one\n    \n    two\n",
                "<p>Test <sup class=\"footnote-ref\"><a href=\"#fn-foo\" id=\"fnref-foo\" data-footnote-ref>1</a></sup></p>\n" +
                        "<section class=\"footnotes\" data-footnotes>\n" +
                        "<ol>\n" +
                        "<li id=\"fn-foo\">\n" +
                        "<p>one</p>\n" +
                        "<p>two <a href=\"#fnref-foo\" class=\"footnote-backref\" data-footnote-backref data-footnote-backref-idx=\"1\" aria-label=\"Back to reference 1\">↩</a></p>\n" +
                        "</li>\n" +
                        "</ol>\n" +
                        "</section>\n");
    }

    @Test
    public void testDefinitionWithList() {
        assertRendering("Test [^foo]\n\n[^foo]:\n    - one\n    - two\n",
                "<p>Test <sup class=\"footnote-ref\"><a href=\"#fn-foo\" id=\"fnref-foo\" data-footnote-ref>1</a></sup></p>\n" +
                        "<section class=\"footnotes\" data-footnotes>\n" +
                        "<ol>\n" +
                        "<li id=\"fn-foo\">\n" +
                        "<ul>\n" +
                        "<li>one</li>\n" +
                        "<li>two</li>\n" +
                        "</ul>\n" +
                        "<a href=\"#fnref-foo\" class=\"footnote-backref\" data-footnote-backref data-footnote-backref-idx=\"1\" aria-label=\"Back to reference 1\">↩</a></li>\n" +
                        "</ol>\n" +
                        "</section>\n");
    }

    // Text in footnote definitions can reference other footnotes, even ones that aren't referenced in the main text.
    // This makes them tricky because it's not enough to just go through the main text for references.
    // And before we can render a definition, we need to know all references (because we add links back to references).
    //
    // In other words, footnotes form a directed graph. Footnotes can reference each other so cycles are possible too.
    //
    // One way to implement it, which is what cmark-gfm does, is to go through the whole document (including definitions)
    // and find all references in order. That guarantees that all definitions are found, but it has strange results for
    // ordering or when the reference is in an unreferenced definition, see tests below.
    // In graph terms, it renders all definitions that have an incoming edge, no matter whether they are connected to
    // the main text or not.
    //
    // The way we implement it is by starting with the footnotes from the main text. Then from the referenced
    // definitions, run a breadth-first search to find all remaining relevant footnote definitions.

    @Test
    public void testNestedFootnotesSimple() {
        assertRendering("[^foo1]\n" +
                "\n" +
                "[^foo1]: one [^foo2]\n" +
                "[^foo2]: two\n", "<p><sup class=\"footnote-ref\"><a href=\"#fn-foo1\" id=\"fnref-foo1\" data-footnote-ref>1</a></sup></p>\n" +
                "<section class=\"footnotes\" data-footnotes>\n" +
                "<ol>\n" +
                "<li id=\"fn-foo1\">\n" +
                "<p>one <sup class=\"footnote-ref\"><a href=\"#fn-foo2\" id=\"fnref-foo2\" data-footnote-ref>2</a></sup> <a href=\"#fnref-foo1\" class=\"footnote-backref\" data-footnote-backref data-footnote-backref-idx=\"1\" aria-label=\"Back to reference 1\">↩</a></p>\n" +
                "</li>\n" +
                "<li id=\"fn-foo2\">\n" +
                "<p>two <a href=\"#fnref-foo2\" class=\"footnote-backref\" data-footnote-backref data-footnote-backref-idx=\"2\" aria-label=\"Back to reference 2\">↩</a></p>\n" +
                "</li>\n" +
                "</ol>\n" +
                "</section>\n");
    }

    @Test
    public void testNestedFootnotesOrder() {
        // GitHub has a strange result here, the definitions are in order: 1. bar, 2. foo.
        // The reason is that the number is done based on all references in document order, including references in
        // definitions. So [^bar] from the first line is first.
        assertRendering("[^foo]: foo [^bar]\n" +
                "\n" +
                "[^foo]\n" +
                "\n" +
                "[^bar]: bar\n", "<p><sup class=\"footnote-ref\"><a href=\"#fn-foo\" id=\"fnref-foo\" data-footnote-ref>1</a></sup></p>\n" +
                "<section class=\"footnotes\" data-footnotes>\n" +
                "<ol>\n" +
                "<li id=\"fn-foo\">\n" +
                "<p>foo <sup class=\"footnote-ref\"><a href=\"#fn-bar\" id=\"fnref-bar\" data-footnote-ref>2</a></sup> <a href=\"#fnref-foo\" class=\"footnote-backref\" data-footnote-backref data-footnote-backref-idx=\"1\" aria-label=\"Back to reference 1\">↩</a></p>\n" +
                "</li>\n" +
                "<li id=\"fn-bar\">\n" +
                "<p>bar <a href=\"#fnref-bar\" class=\"footnote-backref\" data-footnote-backref data-footnote-backref-idx=\"2\" aria-label=\"Back to reference 2\">↩</a></p>\n" +
                "</li>\n" +
                "</ol>\n" +
                "</section>\n");
    }

    @Test
    public void testNestedFootnotesOrder2() {
        assertRendering("[^1]\n" +
                "\n" +
                "[^4]: four\n" +
                "[^3]: three [^4]\n" +
                "[^2]: two [^4]\n" +
                "[^1]: one [^2][^3]\n", "<p><sup class=\"footnote-ref\"><a href=\"#fn-1\" id=\"fnref-1\" data-footnote-ref>1</a></sup></p>\n" +
                "<section class=\"footnotes\" data-footnotes>\n" +
                "<ol>\n" +
                "<li id=\"fn-1\">\n" +
                "<p>one <sup class=\"footnote-ref\"><a href=\"#fn-2\" id=\"fnref-2\" data-footnote-ref>2</a></sup><sup class=\"footnote-ref\"><a href=\"#fn-3\" id=\"fnref-3\" data-footnote-ref>3</a></sup> <a href=\"#fnref-1\" class=\"footnote-backref\" data-footnote-backref data-footnote-backref-idx=\"1\" aria-label=\"Back to reference 1\">↩</a></p>\n" +
                "</li>\n" +
                "<li id=\"fn-2\">\n" +
                "<p>two <sup class=\"footnote-ref\"><a href=\"#fn-4\" id=\"fnref-4\" data-footnote-ref>4</a></sup> <a href=\"#fnref-2\" class=\"footnote-backref\" data-footnote-backref data-footnote-backref-idx=\"2\" aria-label=\"Back to reference 2\">↩</a></p>\n" +
                "</li>\n" +
                "<li id=\"fn-3\">\n" +
                "<p>three <sup class=\"footnote-ref\"><a href=\"#fn-4\" id=\"fnref-4-2\" data-footnote-ref>4</a></sup> <a href=\"#fnref-3\" class=\"footnote-backref\" data-footnote-backref data-footnote-backref-idx=\"3\" aria-label=\"Back to reference 3\">↩</a></p>\n" +
                "</li>\n" +
                "<li id=\"fn-4\">\n" +
                "<p>four <a href=\"#fnref-4\" class=\"footnote-backref\" data-footnote-backref data-footnote-backref-idx=\"4\" aria-label=\"Back to reference 4\">↩</a> <a href=\"#fnref-4-2\" class=\"footnote-backref\" data-footnote-backref data-footnote-backref-idx=\"4-2\" aria-label=\"Back to reference 4-2\"><sup class=\"footnote-ref\">2</sup>↩</a></p>\n" +
                "</li>\n" +
                "</ol>\n" +
                "</section>\n");
    }

    @Test
    public void testNestedFootnotesCycle() {
        // Footnotes can contain cycles, lol.
        assertRendering("[^foo1]\n" +
                "\n" +
                "[^foo1]: one [^foo2]\n" +
                "[^foo2]: two [^foo1]\n", "<p><sup class=\"footnote-ref\"><a href=\"#fn-foo1\" id=\"fnref-foo1\" data-footnote-ref>1</a></sup></p>\n" +
                "<section class=\"footnotes\" data-footnotes>\n" +
                "<ol>\n" +
                "<li id=\"fn-foo1\">\n" +
                "<p>one <sup class=\"footnote-ref\"><a href=\"#fn-foo2\" id=\"fnref-foo2\" data-footnote-ref>2</a></sup> <a href=\"#fnref-foo1\" class=\"footnote-backref\" data-footnote-backref data-footnote-backref-idx=\"1\" aria-label=\"Back to reference 1\">↩</a> <a href=\"#fnref-foo1-2\" class=\"footnote-backref\" data-footnote-backref data-footnote-backref-idx=\"1-2\" aria-label=\"Back to reference 1-2\"><sup class=\"footnote-ref\">2</sup>↩</a></p>\n" +
                "</li>\n" +
                "<li id=\"fn-foo2\">\n" +
                "<p>two <sup class=\"footnote-ref\"><a href=\"#fn-foo1\" id=\"fnref-foo1-2\" data-footnote-ref>1</a></sup> <a href=\"#fnref-foo2\" class=\"footnote-backref\" data-footnote-backref data-footnote-backref-idx=\"2\" aria-label=\"Back to reference 2\">↩</a></p>\n" +
                "</li>\n" +
                "</ol>\n" +
                "</section>\n");
    }

    @Test
    public void testNestedFootnotesUnreferenced() {
        // This should not result in any footnotes, as baz itself isn't referenced.
        // But GitHub renders bar only, with a broken backref, because bar is referenced from foo.
        assertRendering("[^foo]: foo[^bar]\n" +
                "[^bar]: bar\n", "");

        // And here only 1 is rendered.
        assertRendering("[^1]\n" +
                "\n" +
                "[^1]: one\n" +
                "[^foo]: foo[^bar]\n" +
                "[^bar]: bar\n", "<p><sup class=\"footnote-ref\"><a href=\"#fn-1\" id=\"fnref-1\" data-footnote-ref>1</a></sup></p>\n" +
                "<section class=\"footnotes\" data-footnotes>\n" +
                "<ol>\n" +
                "<li id=\"fn-1\">\n" +
                "<p>one <a href=\"#fnref-1\" class=\"footnote-backref\" data-footnote-backref data-footnote-backref-idx=\"1\" aria-label=\"Back to reference 1\">↩</a></p>\n" +
                "</li>\n" +
                "</ol>\n" +
                "</section>\n");
    }

    @Test
    public void testRenderNodesDirectly() {
        // Everything should work as expected when rendering from nodes directly (no parsing step).
        var doc = new Document();
        var p = new Paragraph();
        p.appendChild(new Text("Test "));
        p.appendChild(new FootnoteReference("foo"));
        var def = new FootnoteDefinition("foo");
        var note = new Paragraph();
        note.appendChild(new Text("note!"));
        def.appendChild(note);
        doc.appendChild(p);
        doc.appendChild(def);

        var expected = "<p>Test <sup class=\"footnote-ref\"><a href=\"#fn-foo\" id=\"fnref-foo\" data-footnote-ref>1</a></sup></p>\n" +
                "<section class=\"footnotes\" data-footnotes>\n" +
                "<ol>\n" +
                "<li id=\"fn-foo\">\n" +
                "<p>note! <a href=\"#fnref-foo\" class=\"footnote-backref\" data-footnote-backref data-footnote-backref-idx=\"1\" aria-label=\"Back to reference 1\">↩</a></p>\n" +
                "</li>\n" +
                "</ol>\n" +
                "</section>\n";
        Asserts.assertRendering("", expected, RENDERER.render(doc));
    }

    @Override
    protected String render(String source) {
        return RENDERER.render(PARSER.parse(source));
    }
}
