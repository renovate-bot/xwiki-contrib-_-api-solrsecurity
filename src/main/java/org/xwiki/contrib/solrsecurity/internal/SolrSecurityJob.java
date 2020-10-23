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

import org.xwiki.component.annotation.Component;
import org.xwiki.job.AbstractJob;
import org.xwiki.job.DefaultJobStatus;
import org.xwiki.job.GroupedJob;
import org.xwiki.job.JobGroupPath;
import org.xwiki.job.Request;
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
import org.xwiki.user.group.GroupException;
import org.xwiki.wiki.descriptor.WikiDescriptorManager;
import org.xwiki.wiki.manager.WikiManagerException;

import com.xpn.xwiki.XWikiException;

/**
 * A job in charge of updating the Solr index with allowed groups.
 * 
 * @version $Id: 326c60aae615460ab14ee84fde06ba5588480c5a $
 */
@Component
@Named(SolrSecurityJob.JOBTYPE)
public class SolrSecurityJob extends AbstractJob<SolrSecurityJobRequest, DefaultJobStatus<SolrSecurityJobRequest>>
    implements GroupedJob
{
    /**
     * The type of the job.
     */
    public static final String JOBTYPE = "solrsecurity";

    private static final int DOCUMENT_QUERY_BATCH_SIZE = 100;

    private static final JobGroupPath GROUP_PATH = new JobGroupPath(JOBTYPE, null);

    @Inject
    private WikiDescriptorManager wikis;

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

    @Override
    protected SolrSecurityJobRequest castRequest(Request request)
    {
        SolrSecurityJobRequest indexerRequest;
        if (request instanceof SolrSecurityJobRequest) {
            indexerRequest = (SolrSecurityJobRequest) request;
        } else {
            indexerRequest = new SolrSecurityJobRequest(request);
        }

        return indexerRequest;
    }

    @Override
    public String getType()
    {
        return SolrSecurityJob.JOBTYPE;
    }

    @Override
    public JobGroupPath getGroupPath()
    {
        // We don't want to execute several of those jobs at the same time
        return GROUP_PATH;
    }

    @Override
    protected void runInternal() throws Exception
    {
        try {
            index();
        } finally {
            this.solrStore.commit();
        }
    }

    private void index() throws QueryException, XWikiException, WikiManagerException, GroupException
    {
        // Resolve groups to index
        Collection<DocumentReference> groups;
        if (getRequest().getGroupReference() != null) {
            groups = this.groupManager.getGroups(getRequest().getGroupReference());
        } else {
            // The groups should be resolved later depending on the document's wiki
            groups = null;
        }

        // Index entities
        if (getRequest().getEntity() != null) {
            if (getRequest().getEntity().getType() == EntityType.WIKI) {
                index(new WikiReference(getRequest().getEntity()), groups);
            } else {
                index(getRequest().getEntity(), groups);
            }
        } else {
            for (String wiki : this.wikis.getAllIds()) {
                try {
                    index(new WikiReference(wiki), groups);
                } catch (Exception e) {
                    this.logger.error("Failed to index entities in wiki [{}]", wiki, e);
                }
            }
        }
    }

    private void index(EntityReference entity, Collection<DocumentReference> groups)
        throws XWikiException, QueryException
    {
        WikiReference wiki = new WikiReference(getRequest().getEntity().extractReference(EntityType.WIKI));

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

    private void index(WikiReference wiki, Collection<DocumentReference> groups) throws QueryException, XWikiException
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
