package org.commonmark.internal;

import org.commonmark.internal.inline.*;
import org.commonmark.internal.util.Escaping;
import org.commonmark.internal.util.LinkScanner;
import org.commonmark.node.*;
import org.commonmark.parser.InlineParser;
import org.commonmark.parser.InlineParserContext;
import org.commonmark.parser.SourceLines;
import org.commonmark.parser.beta.Scanner;
import org.commonmark.parser.beta.*;
import org.commonmark.parser.delimiter.DelimiterProcessor;
import org.commonmark.text.Characters;

import java.util.*;

public class InlineParserImpl implements InlineParser, InlineParserState {

    private final InlineParserContext context;
    private final List<InlineContentParserFactory> inlineContentParserFactories;
    private final Map<Character, DelimiterProcessor> delimiterProcessors;
    private final List<BracketProcessor> bracketProcessors;
    private final BitSet specialCharacters;

    private Map<Character, List<InlineContentParser>> inlineParsers;
    private Scanner scanner;
    private boolean includeSourceSpans;
    private int trailingSpaces;

    /**
     * Top delimiter (emphasis, strong emphasis or custom emphasis). (Brackets are on a separate stack, different
     * from the algorithm described in the spec.)
     */
    private Delimiter lastDelimiter;

    /**
     * Top opening bracket (<code>[</code> or <code>![)</code>).
     */
    private Bracket lastBracket;

    public InlineParserImpl(InlineParserContext context) {
        this.context = context;
        this.inlineContentParserFactories = calculateInlineContentParserFactories(context.getCustomInlineContentParserFactories());
        this.delimiterProcessors = calculateDelimiterProcessors(context.getCustomDelimiterProcessors());
        this.bracketProcessors = calculateBracketProcessors(context.getCustomBracketProcessors());
        this.specialCharacters = calculateSpecialCharacters(this.delimiterProcessors.keySet(), this.inlineContentParserFactories);
    }

    private List<InlineContentParserFactory> calculateInlineContentParserFactories(List<InlineContentParserFactory> customFactories) {
        // Custom parsers can override built-in parsers if they want, so make sure they are tried first
        var list = new ArrayList<>(customFactories);
        list.add(new BackslashInlineParser.Factory());
        list.add(new BackticksInlineParser.Factory());
        list.add(new EntityInlineParser.Factory());
        list.add(new AutolinkInlineParser.Factory());
        list.add(new HtmlInlineParser.Factory());
        return list;
    }

    private List<BracketProcessor> calculateBracketProcessors(List<BracketProcessor> bracketProcessors) {
        // Custom bracket processors can override the built-in behavior, so make sure they are tried first
        var list = new ArrayList<>(bracketProcessors);
        list.add(new CoreBracketProcessor());
        return list;
    }

    private static Map<Character, DelimiterProcessor> calculateDelimiterProcessors(List<DelimiterProcessor> delimiterProcessors) {
        var map = new HashMap<Character, DelimiterProcessor>();
        addDelimiterProcessors(List.of(new AsteriskDelimiterProcessor(), new UnderscoreDelimiterProcessor()), map);
        addDelimiterProcessors(delimiterProcessors, map);
        return map;
    }

    private static void addDelimiterProcessors(Iterable<DelimiterProcessor> delimiterProcessors, Map<Character, DelimiterProcessor> map) {
        for (DelimiterProcessor delimiterProcessor : delimiterProcessors) {
            char opening = delimiterProcessor.getOpeningCharacter();
            char closing = delimiterProcessor.getClosingCharacter();
            if (opening == closing) {
                DelimiterProcessor old = map.get(opening);
                if (old != null && old.getOpeningCharacter() == old.getClosingCharacter()) {
                    StaggeredDelimiterProcessor s;
                    if (old instanceof StaggeredDelimiterProcessor) {
                        s = (StaggeredDelimiterProcessor) old;
                    } else {
                        s = new StaggeredDelimiterProcessor(opening);
                        s.add(old);
                    }
                    s.add(delimiterProcessor);
                    map.put(opening, s);
                } else {
                    addDelimiterProcessorForChar(opening, delimiterProcessor, map);
                }
            } else {
                addDelimiterProcessorForChar(opening, delimiterProcessor, map);
                addDelimiterProcessorForChar(closing, delimiterProcessor, map);
            }
        }
    }

    private static void addDelimiterProcessorForChar(char delimiterChar, DelimiterProcessor toAdd, Map<Character, DelimiterProcessor> delimiterProcessors) {
        DelimiterProcessor existing = delimiterProcessors.put(delimiterChar, toAdd);
        if (existing != null) {
            throw new IllegalArgumentException("Delimiter processor conflict with delimiter char '" + delimiterChar + "'");
        }
    }

    private static BitSet calculateSpecialCharacters(Set<Character> delimiterCharacters,
                                                     List<InlineContentParserFactory> inlineContentParserFactories) {
        BitSet bitSet = new BitSet();
        for (Character c : delimiterCharacters) {
            bitSet.set(c);
        }
        for (var factory : inlineContentParserFactories) {
            for (var c : factory.getTriggerCharacters()) {
                bitSet.set(c);
            }
        }
        bitSet.set('[');
        bitSet.set(']');
        bitSet.set('!');
        bitSet.set('\n');
        return bitSet;
    }

    private Map<Character, List<InlineContentParser>> createInlineContentParsers() {
        var map = new HashMap<Character, List<InlineContentParser>>();
        for (var factory : inlineContentParserFactories) {
            var parser = factory.create();
            for (var c : factory.getTriggerCharacters()) {
                map.computeIfAbsent(c, k -> new ArrayList<>()).add(parser);
            }
        }
        return map;
    }

    @Override
    public Scanner scanner() {
        return scanner;
    }

    /**
     * Parse content in block into inline children, appending them to the block node.
     */
    @Override
    public void parse(SourceLines lines, Node block) {
        reset(lines);

        while (true) {
            var nodes = parseInline();
            if (nodes == null) {
                break;
            }
            for (Node node : nodes) {
                block.appendChild(node);
            }
        }

        processDelimiters(null);
        mergeChildTextNodes(block);
    }

    void reset(SourceLines lines) {
        this.scanner = Scanner.of(lines);
        this.includeSourceSpans = !lines.getSourceSpans().isEmpty();
        this.trailingSpaces = 0;
        this.lastDelimiter = null;
        this.lastBracket = null;
        this.inlineParsers = createInlineContentParsers();
    }

    private Text text(SourceLines sourceLines) {
        Text text = new Text(sourceLines.getContent());
        text.setSourceSpans(sourceLines.getSourceSpans());
        return text;
    }

    /**
     * Parse the next inline element in subject, advancing our position.
     * On success, return the new inline node.
     * On failure, return null.
     */
    private List<? extends Node> parseInline() {
        char c = scanner.peek();

        switch (c) {
            case '[':
                return List.of(parseOpenBracket());
            case '!':
                return List.of(parseBang());
            case ']':
                return List.of(parseCloseBracket());
            case '\n':
                return List.of(parseLineBreak());
            case Scanner.END:
                return null;
        }

        // No inline parser, delimiter or other special handling.
        if (!specialCharacters.get(c)) {
            return List.of(parseText());
        }

        List<InlineContentParser> inlineParsers = this.inlineParsers.get(c);
        if (inlineParsers != null) {
            Position position = scanner.position();
            for (InlineContentParser inlineParser : inlineParsers) {
                ParsedInline parsedInline = inlineParser.tryParse(this);
                if (parsedInline instanceof ParsedInlineImpl) {
                    ParsedInlineImpl parsedInlineImpl = (ParsedInlineImpl) parsedInline;
                    Node node = parsedInlineImpl.getNode();
                    scanner.setPosition(parsedInlineImpl.getPosition());
                    if (includeSourceSpans && node.getSourceSpans().isEmpty()) {
                        node.setSourceSpans(scanner.getSource(position, scanner.position()).getSourceSpans());
                    }
                    return List.of(node);
                } else {
                    // Reset position
                    scanner.setPosition(position);
                }
            }
        }

        DelimiterProcessor delimiterProcessor = delimiterProcessors.get(c);
        if (delimiterProcessor != null) {
            List<? extends Node> nodes = parseDelimiters(delimiterProcessor, c);
            if (nodes != null) {
                return nodes;
            }
        }

        // If we get here, even for a special/delimiter character, we will just treat it as text.
        return List.of(parseText());
    }

    /**
     * Attempt to parse delimiters like emphasis, strong emphasis or custom delimiters.
     */
    private List<? extends Node> parseDelimiters(DelimiterProcessor delimiterProcessor, char delimiterChar) {
        DelimiterData res = scanDelimiters(delimiterProcessor, delimiterChar);
        if (res == null) {
            return null;
        }

        List<Text> characters = res.characters;

        // Add entry to stack for this opener
        lastDelimiter = new Delimiter(characters, delimiterChar, res.canOpen, res.canClose, lastDelimiter);
        if (lastDelimiter.previous != null) {
            lastDelimiter.previous.next = lastDelimiter;
        }

        return characters;
    }

    /**
     * Add open bracket to delimiter stack and add a text node to block's children.
     */
    private Node parseOpenBracket() {
        Position start = scanner.position();
        scanner.next();
        Position contentPosition = scanner.position();

        Text node = text(scanner.getSource(start, contentPosition));

        // Add entry to stack for this opener
        addBracket(Bracket.link(node, start, contentPosition, lastBracket, lastDelimiter));

        return node;
    }

    /**
     * If next character is [, and ! delimiter to delimiter stack and add a text node to block's children.
     * Otherwise just add a text node.
     */
    private Node parseBang() {
        Position start = scanner.position();
        scanner.next();
        if (scanner.next('[')) {
            Position contentPosition = scanner.position();
            Text node = text(scanner.getSource(start, contentPosition));

            // Add entry to stack for this opener
            addBracket(Bracket.image(node, start, contentPosition, lastBracket, lastDelimiter));
            return node;
        } else {
            return text(scanner.getSource(start, scanner.position()));
        }
    }

    /**
     * Try to match close bracket against an opening in the delimiter stack. Return either a link or image, or a
     * plain [ character. If there is a matching delimiter, remove it from the delimiter stack.
     */
    private Node parseCloseBracket() {
        Position beforeClose = scanner.position();
        scanner.next();
        Position afterClose = scanner.position();

        // Get previous `[` or `![`
        Bracket opener = lastBracket;
        if (opener == null) {
            // No matching opener, just return a literal.
            return text(scanner.getSource(beforeClose, afterClose));
        }

        if (!opener.allowed) {
            // Matching opener, but it's not allowed, just return a literal.
            removeLastBracket();
            return text(scanner.getSource(beforeClose, afterClose));
        }

        var linkOrImage = parseLinkOrImage(opener, beforeClose);
        if (linkOrImage != null) {
            return linkOrImage;
        }
        scanner.setPosition(afterClose);

        // Nothing parsed, just parse the bracket as text and continue
        removeLastBracket();
        return text(scanner.getSource(beforeClose, afterClose));
    }

    private Node parseLinkOrImage(Bracket opener, Position beforeClose) {
        // Check to see if we have a link (or image, with a ! in front). The different types:
        // - Inline:       `[foo](/uri)` or with optional title `[foo](/uri "title")`
        // - Reference links
        //   - Full:      `[foo][bar]` (foo is the text and bar is the label that needs to match a reference)
        //   - Collapsed: `[foo][]`    (foo is both the text and label)
        //   - Shortcut:  `[foo]`      (foo is both the text and label)

        // Starting position is after the closing `]`
        Position afterClose = scanner.position();

        // Maybe an inline link/image
        var destinationTitle = parseInlineDestinationTitle(scanner);
        if (destinationTitle != null) {
            var linkOrImage = opener.image
                    ? new Image(destinationTitle.destination, destinationTitle.title)
                    : new Link(destinationTitle.destination, destinationTitle.title);
            return processLinkOrImage(opener, linkOrImage);
        }
        // Not an inline link/image, rewind back to after `]`.
        scanner.setPosition(afterClose);

        // Maybe a reference link/image like `[foo][bar]`, `[foo][]` or `[foo]`.
        // Note that even `[foo](` could be a valid link if foo is a reference, which is why we try this even if the `(`
        // failed to be parsed as an inline link/image before.

        String text = scanner.getSource(opener.contentPosition, beforeClose).getContent();

        // See if there's a link label like `[bar]` or `[]`
        String label = parseLinkLabel(scanner);
        if (label == null) {
            // No label, rewind back
            scanner.setPosition(afterClose);
        }
        var referenceType = label == null ?
                BracketInfo.ReferenceType.SHORTCUT : label.isEmpty() ?
                BracketInfo.ReferenceType.COLLAPSED :
                BracketInfo.ReferenceType.FULL;
        if ((referenceType == BracketInfo.ReferenceType.SHORTCUT || referenceType == BracketInfo.ReferenceType.COLLAPSED)
                && opener.bracketAfter) {
            // In case of SHORTCUT or COLLAPSED, the text is used as the reference. But the reference is not allowed to
            // contain an unescaped bracket, so if that's the case we don't need to continue. This is an optimization.
            return null;
        }

        var bracketInfo = new BracketInfo() {
            @Override
            public OpenerType openerType() {
                return opener.image ? OpenerType.IMAGE : OpenerType.LINK;
            }

            @Override
            public ReferenceType referenceType() {
                return referenceType;
            }

            @Override
            public String text() {
                return text;
            }

            @Override
            public String label() {
                return label;
            }

            @Override
            public Position afterTextBracket() {
                return afterClose;
            }
        };

        var processorStartPosition = scanner.position();

        for (var bracketProcessor : bracketProcessors) {
            // TODO: Should inline links also go through this, maybe? That would allow e.g. the image attributes extension
            //  to use it to parse the attributes, I think. It would also be clearer: Every type of link goes through this.
            var bracketResult = bracketProcessor.process(bracketInfo, scanner, context);
            if (!(bracketResult instanceof BracketResultImpl)) {
                // Reset position in case the processor used the scanner, and it didn't work out.
                scanner.setPosition(processorStartPosition);
                continue;
            }

            var result = (BracketResultImpl) bracketResult;
            var node = result.getNode();
            var position = result.getPosition();
            var startFromBracket = result.isStartFromBracket();

            switch (result.getType()) {
                case WRAP:
                    scanner.setPosition(position);
                    // TODO: startFromBracket
                    return processLinkOrImage(opener, node);
                case REPLACE:
                    scanner.setPosition(position);

                    // Remove delimiters (but keep text nodes)
                    while (lastDelimiter != null && lastDelimiter != opener.previousDelimiter) {
                        removeDelimiterKeepNode(lastDelimiter);
                    }

                    removeLastBracket();

                    // TODO: startFromBracket needs to split the opening node.. Maybe we should just keep ! and [
                    //  as separate nodes in Bracket
                    for (Node n = opener.node; n != null; n = n.getNext()) {
                        n.unlink();
                    }
                    return node;
            }
        }

        return null;
    }

    private Node processLinkOrImage(Bracket opener, Node linkOrImage) {
        // Add all nodes between the opening bracket and now (closing bracket) as child nodes of the link
        Node node = opener.node.getNext();
        while (node != null) {
            Node next = node.getNext();
            linkOrImage.appendChild(node);
            node = next;
        }

        if (includeSourceSpans) {
            linkOrImage.setSourceSpans(scanner.getSource(opener.markerPosition, scanner.position()).getSourceSpans());
        }

        // Process delimiters such as emphasis inside link/image
        processDelimiters(opener.previousDelimiter);
        mergeChildTextNodes(linkOrImage);
        // We don't need the corresponding text node anymore, we turned it into a link/image node
        opener.node.unlink();
        removeLastBracket();

        // Links within links are not allowed. We found this link, so there can be no other link around it.
        if (!opener.image) {
            Bracket bracket = lastBracket;
            while (bracket != null) {
                if (!bracket.image) {
                    // Disallow link opener. It will still get matched, but will not result in a link.
                    bracket.allowed = false;
                }
                bracket = bracket.previous;
            }
        }

        return linkOrImage;
    }

    private void addBracket(Bracket bracket) {
        if (lastBracket != null) {
            lastBracket.bracketAfter = true;
        }
        lastBracket = bracket;
    }

    private void removeLastBracket() {
        lastBracket = lastBracket.previous;
    }

    /**
     * Try to parse the destination and an optional title for an inline link/image.
     */
    private static DestinationTitle parseInlineDestinationTitle(Scanner scanner) {
        if (!scanner.next('(')) {
            return null;
        }

        scanner.whitespace();
        String dest = parseLinkDestination(scanner);
        if (dest == null) {
            return null;
        }

        String title = null;
        int whitespace = scanner.whitespace();
        // title needs a whitespace before
        if (whitespace >= 1) {
            title = parseLinkTitle(scanner);
            scanner.whitespace();
        }
        if (!scanner.next(')')) {
            // Don't have a closing `)`, so it's not a destination and title.
            // Note that something like `[foo](` could still be valid later, `(` will just be text.
            return null;
        }
        return new DestinationTitle(dest, title);
    }

    /**
     * Attempt to parse link destination, returning the string or null if no match.
     */
    private static String parseLinkDestination(Scanner scanner) {
        char delimiter = scanner.peek();
        Position start = scanner.position();
        if (!LinkScanner.scanLinkDestination(scanner)) {
            return null;
        }

        String dest;
        if (delimiter == '<') {
            // chop off surrounding <..>:
            String rawDestination = scanner.getSource(start, scanner.position()).getContent();
            dest = rawDestination.substring(1, rawDestination.length() - 1);
        } else {
            dest = scanner.getSource(start, scanner.position()).getContent();
        }

        return Escaping.unescapeString(dest);
    }

    /**
     * Attempt to parse link title (sans quotes), returning the string or null if no match.
     */
    private static String parseLinkTitle(Scanner scanner) {
        Position start = scanner.position();
        if (!LinkScanner.scanLinkTitle(scanner)) {
            return null;
        }

        // chop off ', " or parens
        String rawTitle = scanner.getSource(start, scanner.position()).getContent();
        String title = rawTitle.substring(1, rawTitle.length() - 1);
        return Escaping.unescapeString(title);
    }

    /**
     * Attempt to parse a link label, returning the label between the brackets or null.
     */
    static String parseLinkLabel(Scanner scanner) {
        if (!scanner.next('[')) {
            return null;
        }

        Position start = scanner.position();
        if (!LinkScanner.scanLinkLabelContent(scanner)) {
            return null;
        }
        Position end = scanner.position();

        if (!scanner.next(']')) {
            return null;
        }

        String content = scanner.getSource(start, end).getContent();
        // spec: A link label can have at most 999 characters inside the square brackets.
        if (content.length() > 999) {
            return null;
        }

        return content;
    }

    private Node parseLineBreak() {
        scanner.next();

        if (trailingSpaces >= 2) {
            return new HardLineBreak();
        } else {
            return new SoftLineBreak();
        }
    }

    /**
     * Parse the next character as plain text, and possibly more if the following characters are non-special.
     */
    private Node parseText() {
        Position start = scanner.position();
        scanner.next();
        char c;
        while (true) {
            c = scanner.peek();
            if (c == Scanner.END || specialCharacters.get(c)) {
                break;
            }
            scanner.next();
        }

        SourceLines source = scanner.getSource(start, scanner.position());
        String content = source.getContent();

        if (c == '\n') {
            // We parsed until the end of the line. Trim any trailing spaces and remember them (for hard line breaks).
            int end = Characters.skipBackwards(' ', content, content.length() - 1, 0) + 1;
            trailingSpaces = content.length() - end;
            content = content.substring(0, end);
        } else if (c == Scanner.END) {
            // For the last line, both tabs and spaces are trimmed for some reason (checked with commonmark.js).
            int end = Characters.skipSpaceTabBackwards(content, content.length() - 1, 0) + 1;
            content = content.substring(0, end);
        }

        Text text = new Text(content);
        text.setSourceSpans(source.getSourceSpans());
        return text;
    }

    /**
     * Scan a sequence of characters with code delimiterChar, and return information about the number of delimiters
     * and whether they are positioned such that they can open and/or close emphasis or strong emphasis.
     *
     * @return information about delimiter run, or {@code null}
     */
    private DelimiterData scanDelimiters(DelimiterProcessor delimiterProcessor, char delimiterChar) {
        int before = scanner.peekPreviousCodePoint();
        Position start = scanner.position();

        // Quick check to see if we have enough delimiters.
        int delimiterCount = scanner.matchMultiple(delimiterChar);
        if (delimiterCount < delimiterProcessor.getMinLength()) {
            scanner.setPosition(start);
            return null;
        }

        // We do have enough, extract a text node for each delimiter character.
        List<Text> delimiters = new ArrayList<>();
        scanner.setPosition(start);
        Position positionBefore = start;
        while (scanner.next(delimiterChar)) {
            delimiters.add(text(scanner.getSource(positionBefore, scanner.position())));
            positionBefore = scanner.position();
        }

        int after = scanner.peekCodePoint();

        // We could be more lazy here, in most cases we don't need to do every match case.
        boolean beforeIsPunctuation = before == Scanner.END || Characters.isPunctuationCodePoint(before);
        boolean beforeIsWhitespace = before == Scanner.END || Characters.isWhitespaceCodePoint(before);
        boolean afterIsPunctuation = after == Scanner.END || Characters.isPunctuationCodePoint(after);
        boolean afterIsWhitespace = after == Scanner.END || Characters.isWhitespaceCodePoint(after);

        boolean leftFlanking = !afterIsWhitespace &&
                (!afterIsPunctuation || beforeIsWhitespace || beforeIsPunctuation);
        boolean rightFlanking = !beforeIsWhitespace &&
                (!beforeIsPunctuation || afterIsWhitespace || afterIsPunctuation);
        boolean canOpen;
        boolean canClose;
        if (delimiterChar == '_') {
            canOpen = leftFlanking && (!rightFlanking || beforeIsPunctuation);
            canClose = rightFlanking && (!leftFlanking || afterIsPunctuation);
        } else {
            canOpen = leftFlanking && delimiterChar == delimiterProcessor.getOpeningCharacter();
            canClose = rightFlanking && delimiterChar == delimiterProcessor.getClosingCharacter();
        }

        return new DelimiterData(delimiters, canOpen, canClose);
    }

    private void processDelimiters(Delimiter stackBottom) {

        Map<Character, Delimiter> openersBottom = new HashMap<>();

        // find first closer above stackBottom:
        Delimiter closer = lastDelimiter;
        while (closer != null && closer.previous != stackBottom) {
            closer = closer.previous;
        }
        // move forward, looking for closers, and handling each
        while (closer != null) {
            char delimiterChar = closer.delimiterChar;

            DelimiterProcessor delimiterProcessor = delimiterProcessors.get(delimiterChar);
            if (!closer.canClose() || delimiterProcessor == null) {
                closer = closer.next;
                continue;
            }

            char openingDelimiterChar = delimiterProcessor.getOpeningCharacter();

            // Found delimiter closer. Now look back for first matching opener.
            int usedDelims = 0;
            boolean openerFound = false;
            boolean potentialOpenerFound = false;
            Delimiter opener = closer.previous;
            while (opener != null && opener != stackBottom && opener != openersBottom.get(delimiterChar)) {
                if (opener.canOpen() && opener.delimiterChar == openingDelimiterChar) {
                    potentialOpenerFound = true;
                    usedDelims = delimiterProcessor.process(opener, closer);
                    if (usedDelims > 0) {
                        openerFound = true;
                        break;
                    }
                }
                opener = opener.previous;
            }

            if (!openerFound) {
                if (!potentialOpenerFound) {
                    // Set lower bound for future searches for openers.
                    // Only do this when we didn't even have a potential
                    // opener (one that matches the character and can open).
                    // If an opener was rejected because of the number of
                    // delimiters (e.g. because of the "multiple of 3" rule),
                    // we want to consider it next time because the number
                    // of delimiters can change as we continue processing.
                    openersBottom.put(delimiterChar, closer.previous);
                    if (!closer.canOpen()) {
                        // We can remove a closer that can't be an opener,
                        // once we've seen there's no matching opener:
                        removeDelimiterKeepNode(closer);
                    }
                }
                closer = closer.next;
                continue;
            }

            // Remove number of used delimiters nodes.
            for (int i = 0; i < usedDelims; i++) {
                Text delimiter = opener.characters.remove(opener.characters.size() - 1);
                delimiter.unlink();
            }
            for (int i = 0; i < usedDelims; i++) {
                Text delimiter = closer.characters.remove(0);
                delimiter.unlink();
            }

            removeDelimitersBetween(opener, closer);

            // No delimiter characters left to process, so we can remove delimiter and the now empty node.
            if (opener.length() == 0) {
                removeDelimiterAndNodes(opener);
            }

            if (closer.length() == 0) {
                Delimiter next = closer.next;
                removeDelimiterAndNodes(closer);
                closer = next;
            }
        }

        // remove all delimiters
        while (lastDelimiter != null && lastDelimiter != stackBottom) {
            removeDelimiterKeepNode(lastDelimiter);
        }
    }

    private void removeDelimitersBetween(Delimiter opener, Delimiter closer) {
        Delimiter delimiter = closer.previous;
        while (delimiter != null && delimiter != opener) {
            Delimiter previousDelimiter = delimiter.previous;
            removeDelimiterKeepNode(delimiter);
            delimiter = previousDelimiter;
        }
    }

    /**
     * Remove the delimiter and the corresponding text node. For used delimiters, e.g. `*` in `*foo*`.
     */
    private void removeDelimiterAndNodes(Delimiter delim) {
        removeDelimiter(delim);
    }

    /**
     * Remove the delimiter but keep the corresponding node as text. For unused delimiters such as `_` in `foo_bar`.
     */
    private void removeDelimiterKeepNode(Delimiter delim) {
        removeDelimiter(delim);
    }

    private void removeDelimiter(Delimiter delim) {
        if (delim.previous != null) {
            delim.previous.next = delim.next;
        }
        if (delim.next == null) {
            // top of stack
            lastDelimiter = delim.previous;
        } else {
            delim.next.previous = delim.previous;
        }
    }

    private void mergeChildTextNodes(Node node) {
        // No children, no need for merging
        if (node.getFirstChild() == null) {
            return;
        }

        mergeTextNodesInclusive(node.getFirstChild(), node.getLastChild());
    }

    private void mergeTextNodesInclusive(Node fromNode, Node toNode) {
        Text first = null;
        Text last = null;
        int length = 0;

        Node node = fromNode;
        while (node != null) {
            if (node instanceof Text) {
                Text text = (Text) node;
                if (first == null) {
                    first = text;
                }
                length += text.getLiteral().length();
                last = text;
            } else {
                mergeIfNeeded(first, last, length);
                first = null;
                last = null;
                length = 0;

                mergeChildTextNodes(node);
            }
            if (node == toNode) {
                break;
            }
            node = node.getNext();
        }

        mergeIfNeeded(first, last, length);
    }

    private void mergeIfNeeded(Text first, Text last, int textLength) {
        if (first != null && last != null && first != last) {
            StringBuilder sb = new StringBuilder(textLength);
            sb.append(first.getLiteral());
            SourceSpans sourceSpans = null;
            if (includeSourceSpans) {
                sourceSpans = new SourceSpans();
                sourceSpans.addAll(first.getSourceSpans());
            }
            Node node = first.getNext();
            Node stop = last.getNext();
            while (node != stop) {
                sb.append(((Text) node).getLiteral());
                if (sourceSpans != null) {
                    sourceSpans.addAll(node.getSourceSpans());
                }

                Node unlink = node;
                node = node.getNext();
                unlink.unlink();
            }
            String literal = sb.toString();
            first.setLiteral(literal);
            if (sourceSpans != null) {
                first.setSourceSpans(sourceSpans.getSourceSpans());
            }
        }
    }

    private static class DelimiterData {

        final List<Text> characters;
        final boolean canClose;
        final boolean canOpen;

        DelimiterData(List<Text> characters, boolean canOpen, boolean canClose) {
            this.characters = characters;
            this.canOpen = canOpen;
            this.canClose = canClose;
        }
    }

    /**
     * A destination and optional title for a link or image.
     */
    private static class DestinationTitle {
        final String destination;
        final String title;

        public DestinationTitle(String destination, String title) {
            this.destination = destination;
            this.title = title;
        }
    }
}
