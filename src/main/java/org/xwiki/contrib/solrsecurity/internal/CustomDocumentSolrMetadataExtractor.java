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

import org.apache.solr.common.SolrInputDocument;
import org.slf4j.Logger;
import org.xwiki.component.annotation.Component;
import org.xwiki.search.solr.SolrEntityMetadataExtractor;
import org.xwiki.search.solr.internal.metadata.DocumentSolrMetadataExtractor;

import com.xpn.xwiki.XWikiException;
import com.xpn.xwiki.doc.XWikiDocument;

/**
 * Inject Overwrite the standard {@link DocumentSolrMetadataExtractor} to trigger the injection of the "allowed" field.
 * 
 * @version $Id$
 */
@Component
@Named("document")
@Singleton
public class CustomDocumentSolrMetadataExtractor implements SolrEntityMetadataExtractor<XWikiDocument>
{
    @Inject
    private SolrSecurityIndexer indexer;

    @Inject
    private Logger logger;

    @Override
    public boolean extract(XWikiDocument entity, SolrInputDocument solrDocument)
    {
        try {
            this.indexer.index(entity.getDocumentReference(), solrDocument);
        } catch (XWikiException e) {
            this.logger.error("Failed to index the right for document [{}]", entity.getDocumentReference(), e);
        }

        return true;
    }
}
