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

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;

import org.xwiki.component.annotation.Component;
import org.xwiki.model.EntityType;
import org.xwiki.model.reference.DocumentReference;
import org.xwiki.model.reference.DocumentReferenceResolver;
import org.xwiki.model.reference.EntityReference;
import org.xwiki.model.reference.EntityReferenceSerializer;
import org.xwiki.model.reference.WikiReference;
import org.xwiki.query.Query;
import org.xwiki.query.QueryException;
import org.xwiki.query.QueryManager;
import org.xwiki.security.authorization.AuthorizationManager;
import org.xwiki.security.authorization.Right;

import com.xpn.xwiki.XWikiException;

/**
 * A tool in charge of updating the Solr index with allowed groups.
 * 
 * @version $Id: 326c60aae615460ab14ee84fde06ba5588480c5a $
 */
@Component(roles = SolrSecurityIndexer.class)
@Singleton
public class SolrSecurityIndexer
{
    private static final int DOCUMENT_QUERY_BATCH_SIZE = 100;

    @Inject
    private DocumentReferenceResolver<String> documentResolver;

    @Inject
    @Named("local")
    private EntityReferenceSerializer<String> localSerializer;

    @Inject
    private SolrSecurityGroupManager groupManager;

    @Inject
    private QueryManager queryManager;

    @Inject
    private AuthorizationManager authorization;

    @Inject
    private SolrSecurityStore solrStore;

    /**
     * @param entity the entity to index
     * @param groups the groups to index
     * @throws XWikiException when failing to use the XWiki API
     * @throws QueryException when failing to use execute database request
     */
    public void index(EntityReference entity, Collection<DocumentReference> groups)
        throws XWikiException, QueryException
    {
        WikiReference wiki = new WikiReference(entity.extractReference(EntityType.WIKI));

        Collection<DocumentReference> finalGroups = groups;

        if (finalGroups == null) {
            finalGroups = this.groupManager.getGroups(wiki);
        }

        if (entity.getType() == EntityType.SPACE) {
            index(this.localSerializer.serialize(entity), wiki, finalGroups);
        } else if (entity instanceof DocumentReference) {
            index((DocumentReference) entity, finalGroups);
        } else if (entity.getType() == EntityType.DOCUMENT) {
            index(new DocumentReference(entity), finalGroups);
        }
    }

    /**
     * @param wiki the wiki to index
     * @param groups the groups to index
     * @throws XWikiException when failing to use the XWiki API
     * @throws QueryException when failing to use execute database request
     */
    public void index(WikiReference wiki, Collection<DocumentReference> groups) throws QueryException, XWikiException
    {
        Collection<DocumentReference> finalGroups = groups;

        if (finalGroups == null) {
            finalGroups = this.groupManager.getGroups(wiki);
        }

        Query query = this.queryManager.getNamedQuery("getAllDocuments");

        query.setWiki(wiki.getName());
        query.setLimit(DOCUMENT_QUERY_BATCH_SIZE);

        int offset = 0;
        do {
            query.setOffset(offset);

            List<String> documentNames = query.execute();

            for (String documentName : documentNames) {
                index(this.documentResolver.resolve(documentName, wiki), finalGroups);
            }

            offset += documentNames.size();
        } while ((offset % DOCUMENT_QUERY_BATCH_SIZE) == 0);
    }

    private void index(String space, WikiReference wiki, Collection<DocumentReference> groups) throws QueryException
    {
        // Index documents
        List<DocumentReference> documents = getDocumentReferences(space, wiki);
        for (DocumentReference document : documents) {
            index(document, groups);
        }

        // Index spaces
        for (String childSpace : getSpaces(space, wiki.getName())) {
            index(childSpace, wiki, groups);
        }
    }

    private List<String> getSpaces(String space, String wiki) throws QueryException
    {
        // Get the spaces
        Query query = this.queryManager
            .createQuery("select distinct space.reference from Space space where space.parent = :parent", Query.XWQL);
        query.bindValue("parent", space);
        query.setWiki(wiki);

        return query.execute();
    }

    private List<DocumentReference> getDocumentReferences(String space, WikiReference wiki) throws QueryException
    {
        Query query = this.queryManager
            .createQuery("select distinct doc.fullName from Document doc where doc.space = :space", Query.XWQL);
        query.bindValue("space", space);
        query.setWiki(wiki.getName());

        return query.<String>execute().stream().map(name -> this.documentResolver.resolve(name, wiki))
            .collect(Collectors.toList());
    }

    private void index(DocumentReference document, Collection<DocumentReference> groups)
    {
        List<DocumentReference> allowedGroups = new ArrayList<>(groups.size());
        List<DocumentReference> deniedGroups = new ArrayList<>(groups.size());

        for (DocumentReference group : groups) {
            if (this.authorization.hasAccess(Right.VIEW, group, document)) {
                allowedGroups.add(group);
            } else {
                deniedGroups.add(group);
            }
        }

        this.solrStore.update(document, allowedGroups, deniedGroups);
    }
}
