package org.janelia.alignment.docs;

import java.io.IOException;

import io.github.robwin.markup.builder.MarkupLanguage;
import io.github.robwin.swagger2markup.GroupBy;
import io.github.robwin.swagger2markup.OrderBy;
import io.github.robwin.swagger2markup.Swagger2MarkupConverter;

/**
 * Invokes {@link Swagger2MarkupConverter} instance to generate static API documentation.
 *
 * @author Eric Trautman
 */
public class Swagger2Markdown {

    public static void main(final String[] args) {
        try {
            convertSwaggerToMarkdown("src/site/resources/swagger.json",
                                     "src/site/markdown/render-ws-api");
        } catch (final IOException e) {
            e.printStackTrace();
        }

    }

    public static void convertSwaggerToMarkdown(final String swaggerJsonUrlString,
                                                final String targetFolderPath)
            throws IOException {

        Swagger2MarkupConverter.from(swaggerJsonUrlString)
                .withMarkupLanguage(MarkupLanguage.MARKDOWN)
                .withPathsGroupedBy(GroupBy.TAGS)
                .withDefinitionsOrderedBy(OrderBy.NATURAL)
                .build()
                .intoFolder(targetFolderPath);

    }
}
