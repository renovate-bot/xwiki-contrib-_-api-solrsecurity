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

import java.util.Collection;

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
import org.xwiki.model.reference.EntityReferenceSerializer;
import org.xwiki.model.reference.WikiReference;
import org.xwiki.query.QueryException;
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

    private static final JobGroupPath GROUP_PATH = new JobGroupPath(JOBTYPE, null);

    @Inject
    private WikiDescriptorManager wikis;

    @Inject
    @Named("local")
    private EntityReferenceSerializer<String> localSerializer;

    @Inject
    private SolrSecurityGroupManager groupManager;

    @Inject
    private SolrSecurityStore solrStore;

    @Inject
    private SolrSecurityIndexer indexer;

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
        Thread currentThread = Thread.currentThread();
        int currentPriority = currentThread.getPriority();

        try {
            // Use a lower priority for the thread to not impact the rest of the farm
            currentThread.setPriority(Thread.NORM_PRIORITY - 1);

            index();
        } finally {
            this.solrStore.commit();

            currentThread.setPriority(currentPriority);
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
                this.indexer.index(new WikiReference(getRequest().getEntity()), groups);
            } else {
                this.indexer.index(getRequest().getEntity(), groups);
            }
        } else {
            for (String wiki : this.wikis.getAllIds()) {
                try {
                    this.indexer.index(new WikiReference(wiki), groups);
                } catch (Exception e) {
                    this.logger.error("Failed to index entities in wiki [{}]", wiki, e);
                }
            }
        }
    }
}
