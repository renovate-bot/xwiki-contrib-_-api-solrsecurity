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
import javax.inject.Singleton;

import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.SolrServerException;
import org.apache.solr.client.solrj.request.schema.SchemaRequest;
import org.apache.solr.common.SolrInputDocument;
import org.apache.solr.schema.FieldType;
import org.slf4j.Logger;
import org.xwiki.component.annotation.Component;
import org.xwiki.component.phase.Initializable;
import org.xwiki.component.phase.InitializationException;
import org.xwiki.model.reference.DocumentReference;
import org.xwiki.model.reference.EntityReferenceSerializer;
import org.xwiki.search.solr.Solr;
import org.xwiki.search.solr.SolrException;
import org.xwiki.search.solr.SolrUtils;

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

    @Inject
    private Solr solr;

    @Inject
    private SolrUtils solrUtils;

    @Inject
    private EntityReferenceSerializer<String> serializer;

    @Inject
    private Logger logger;

    private SolrClient searchClient;

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
     */
    public void update(DocumentReference document, List<DocumentReference> allowedGroups,
        List<DocumentReference> deniedGroups)
    {
        SolrInputDocument solrDocument = new SolrInputDocument();

        this.solrUtils.set("id", this.serializer.serialize(document) + '_', solrDocument);

        this.solrUtils.setAtomic(SolrUtils.ATOMIC_UPDATE_MODIFIER_ADD_DISTINCT, SOLR_FIELD, allowedGroups,
            solrDocument);
        this.solrUtils.setAtomic(SolrUtils.ATOMIC_UPDATE_MODIFIER_REMOVE, SOLR_FIELD, deniedGroups, solrDocument);

        try {
            this.searchClient.add(solrDocument);
        } catch (Exception e) {
            this.logger.error("Failed to update solr document", e);
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
        this.searchClient.commit();
    }
}
