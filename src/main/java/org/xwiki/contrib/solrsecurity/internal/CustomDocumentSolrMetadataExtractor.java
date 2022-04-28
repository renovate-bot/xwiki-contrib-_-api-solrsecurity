/*
 * See the NOTICE file distributed with this work for additional
 * information regarding copyright ownership.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */
package org.xwiki.contrib.solrsecurity.internal;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;

import org.xwiki.component.annotation.Component;
import org.xwiki.model.reference.DocumentReference;
import org.xwiki.model.reference.EntityReference;
import org.xwiki.search.solr.internal.metadata.DocumentSolrMetadataExtractor;
import org.xwiki.search.solr.internal.metadata.LengthSolrInputDocument;

/**
 * Overwrite the standard {@link DocumentSolrMetadataExtractor} to trigger the injection of the "allowed" field.
 * <p>
 * TODO: introduce an extension point in XWiki Standard to avoid manipulating internal classes here.
 * 
 * @version $Id$
 */
@Component
@Named("document")
@Singleton
public class CustomDocumentSolrMetadataExtractor extends DocumentSolrMetadataExtractor
{
    @Inject
    private SolrSecurityIndexer indexer;

    @Override
    public boolean setFieldsInternal(LengthSolrInputDocument solrDocument, EntityReference entityReference)
        throws Exception
    {
        boolean indexed = super.setFieldsInternal(solrDocument, entityReference);

        // Inject information about groups access to the document
        if (indexed) {
            DocumentReference documentReference = new DocumentReference(entityReference);

            this.indexer.index(documentReference, solrDocument);
        }

        return indexed;
    }
}
