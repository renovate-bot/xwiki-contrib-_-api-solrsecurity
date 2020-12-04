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

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;

import org.apache.commons.lang3.StringUtils;
import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.SolrServerException;
import org.apache.solr.client.solrj.request.schema.SchemaRequest;
import org.apache.solr.common.SolrInputDocument;
import org.apache.solr.schema.FieldType;
import org.slf4j.Logger;
import org.xwiki.component.annotation.Component;
import org.xwiki.component.phase.Initializable;
import org.xwiki.component.phase.InitializationException;
import org.xwiki.search.solr.Solr;
import org.xwiki.search.solr.SolrException;
import org.xwiki.search.solr.SolrUtils;
import org.xwiki.search.solr.internal.reference.SolrReferenceResolver;

/**
 * Default implementation of {@link SolrSecurityStore}.
 * 
 * @version $Id: 326c60aae615460ab14ee84fde06ba5588480c5a $
 */
@Component(roles = SolrSecurityStore.class)
@Singleton
public class SolrSecurityStore implements Initializable
{
    private static final String SOLR_FIELD = "allowed";

    private static final int BATCH_COMMIT_SIZE = 100;

    @Inject
    private Solr solr;

    @Inject
    private SolrUtils solrUtils;

    @Inject
    private Logger logger;

    @Inject
    @Named("document")
    private SolrReferenceResolver solrResolver;

    private SolrClient searchClient;

    private int count;

    @Override
    public void initialize() throws InitializationException
    {
        try {
            this.searchClient = this.solr.getClient("search");
        } catch (SolrException e) {
            throw new InitializationException("Failed to get the Solr search core client", e);
        }

        // Make sure the schema contain the required field
        try {
            new SchemaRequest.Field(SOLR_FIELD).process(this.searchClient);
        } catch (Exception e) {
            // Try to create it
            Map<String, Object> fieldAttributes = new HashMap<>();
            fieldAttributes.put("name", SOLR_FIELD);
            fieldAttributes.put(FieldType.TYPE, "string");
            fieldAttributes.put("multiValued", true);

            try {
                new SchemaRequest.AddField(fieldAttributes).process(this.searchClient);

                this.searchClient.commit();
            } catch (Exception e1) {
                throw new InitializationException("Faield add the file [" + SOLR_FIELD + "] in the Solr search core",
                    e);
            }
        }
    }

    /**
     * @param document the document to update
     * @param allowedGroups the list of groups allowed to read this document
     * @param deniedGroups the list of groups denied to read this document
     * @param locales the locales of the document to update
     */
    public void update(String document, List<String> locales, List<String> allowedGroups, List<String> deniedGroups)
    {
        SolrInputDocument solrDocument = new SolrInputDocument();

        for (String locale : locales) {
            this.solrUtils.set("id", document + '_' + (StringUtils.isEmpty(locale) ? "" : locale), solrDocument);

            Map<String, List<String>> value = new HashMap<>();
            if (!allowedGroups.isEmpty()) {
                value.put(SolrUtils.ATOMIC_UPDATE_MODIFIER_ADD_DISTINCT, allowedGroups);
            }
            if (!deniedGroups.isEmpty()) {
                value.put(SolrUtils.ATOMIC_UPDATE_MODIFIER_REMOVE, deniedGroups);
            }
            solrDocument.setField(SOLR_FIELD, value);

            try {
                this.searchClient.add(solrDocument);

                ++this.count;

                if (this.count >= BATCH_COMMIT_SIZE) {
                    this.commit();
                }
            } catch (Exception e) {
                this.logger.error("Failed to update solr document", e);
            }
        }
    }

    /**
     * Performs an explicit commit, causing pending documents to be committed for indexing.
     * 
     * @throws IOException If there is a low-level I/O error.
     * @throws SolrServerException if there is an error on the server
     */
    public void commit() throws SolrServerException, IOException
    {
        this.count = 0;

        this.searchClient.commit();
    }
}
