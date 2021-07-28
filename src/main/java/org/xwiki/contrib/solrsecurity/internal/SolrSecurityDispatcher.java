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

import java.util.List;

import javax.inject.Inject;
import javax.inject.Singleton;

import org.slf4j.Logger;
import org.xwiki.component.annotation.Component;
import org.xwiki.job.JobException;
import org.xwiki.job.JobExecutor;
import org.xwiki.job.JobStatusStore;
import org.xwiki.job.event.status.JobStatus;
import org.xwiki.job.event.status.JobStatus.State;
import org.xwiki.model.reference.DocumentReference;
import org.xwiki.model.reference.EntityReference;

/**
 * Trigger various indexing jobs depending on input information.
 * 
 * @version $Id$
 */
@Component(roles = SolrSecurityDispatcher.class)
@Singleton
public class SolrSecurityDispatcher
{
    @Inject
    private JobExecutor jobs;

    @Inject
    private JobStatusStore jobsStore;

    @Inject
    private Logger logger;

    private boolean shouldIndex(List<String> id, boolean force)
    {
        if (force) {
            return true;
        }

        if (this.jobs.getJob(id) != null) {
            return false;
        }

        JobStatus status = this.jobsStore.getJobStatus(id);

        return status == null || status.getState() != State.FINISHED;
    }

    /**
     * @param reference the reference of the entity for which associated rights might have changed
     * @param force true for force indexing
     */
    public void indexEntity(EntityReference reference, boolean force)
    {
        List<String> id = SolrSecurityJobRequest.getIdForEntity(reference);

        if (shouldIndex(id, force)) {
            SolrSecurityJobRequest request = new SolrSecurityJobRequest();

            request.setId(id);

            request.setEntity(reference);

            execute(request);
        }
    }

    /**
     * @param groupReference the reference of the group for which rights might have changed
     */
    public void indexGroup(DocumentReference groupReference)
    {
        SolrSecurityJobRequest request = new SolrSecurityJobRequest();

        request.setId(SolrSecurityJobRequest.getIdForGroup(groupReference));

        request.setGroupReference(groupReference);

        execute(request);
    }

    private void execute(SolrSecurityJobRequest request)
    {
        try {
            this.jobs.execute(SolrSecurityJob.JOBTYPE, request);
        } catch (JobException e) {
            this.logger.error("Failed to start the job for request [{}]", request, e);
        }
    }
}
