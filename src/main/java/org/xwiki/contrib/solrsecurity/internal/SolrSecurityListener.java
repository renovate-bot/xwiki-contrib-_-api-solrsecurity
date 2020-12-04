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
import javax.inject.Named;
import javax.inject.Singleton;

import org.slf4j.Logger;
import org.xwiki.bridge.event.ApplicationReadyEvent;
import org.xwiki.bridge.event.DocumentCreatedEvent;
import org.xwiki.bridge.event.WikiDeletedEvent;
import org.xwiki.bridge.event.WikiEvent;
import org.xwiki.bridge.event.WikiReadyEvent;
import org.xwiki.component.annotation.Component;
import org.xwiki.job.JobException;
import org.xwiki.job.JobExecutor;
import org.xwiki.job.JobStatusStore;
import org.xwiki.job.event.status.JobStatus;
import org.xwiki.job.event.status.JobStatus.State;
import org.xwiki.model.reference.DocumentReference;
import org.xwiki.model.reference.DocumentReferenceResolver;
import org.xwiki.model.reference.EntityReference;
import org.xwiki.model.reference.LocalDocumentReference;
import org.xwiki.model.reference.RegexEntityReference;
import org.xwiki.model.reference.WikiReference;
import org.xwiki.observation.AbstractEventListener;
import org.xwiki.observation.event.Event;

import com.xpn.xwiki.XWiki;
import com.xpn.xwiki.XWikiContext;
import com.xpn.xwiki.doc.XWikiDocument;
import com.xpn.xwiki.internal.event.XObjectAddedEvent;
import com.xpn.xwiki.internal.event.XObjectDeletedEvent;
import com.xpn.xwiki.internal.event.XObjectEvent;
import com.xpn.xwiki.internal.event.XObjectUpdatedEvent;
import com.xpn.xwiki.internal.mandatory.XWikiGlobalRightsDocumentInitializer;
import com.xpn.xwiki.internal.mandatory.XWikiRightsDocumentInitializer;
import com.xpn.xwiki.objects.BaseObject;
import com.xpn.xwiki.objects.BaseObjectReference;

/**
 * Listener to document changes and store them.
 * 
 * @version $Id$
 */
@Component
@Named(SolrSecurityListener.NAME)
@Singleton
public class SolrSecurityListener extends AbstractEventListener
{
    /**
     * The unique name of the listener.
     */
    public static final String NAME = "SolrSecurityListener";

    private static final LocalDocumentReference LOCAL_GROUP_REFERENCE =
        new LocalDocumentReference(XWiki.SYSTEM_SPACE, "XWikiGroups");

    private static final String SPACEPREFERENCE_NAME = "WebPreferences";

    private static final LocalDocumentReference WIKIPREFERENCE_REFERENCE =
        new LocalDocumentReference(XWiki.SYSTEM_SPACE, "XWikiPreferences");

    private static final RegexEntityReference GROUP_REFERENCE = BaseObjectReference.any("XWiki.XWikiGroups");

    private static final RegexEntityReference GLOBALRIGHT_REFERENCE =
        BaseObjectReference.any(XWikiGlobalRightsDocumentInitializer.CLASS_REFERENCE_STRING);

    private static final RegexEntityReference RIGHT_REFERENCE =
        BaseObjectReference.any(XWikiRightsDocumentInitializer.CLASS_REFERENCE_STRING);

    private static final String GROUP_MEMBER = "member";

    @Inject
    private Logger logger;

    @Inject
    private JobExecutor jobs;

    @Inject
    private JobStatusStore jobsStore;

    @Inject
    private DocumentReferenceResolver<String> documentResolver;

    @Inject
    private SolrSecurityGroupManager groupManager;

    /**
     * The default constructor.
     */
    public SolrSecurityListener()
    {
        super(NAME, new ApplicationReadyEvent(), new WikiReadyEvent(), new WikiDeletedEvent(),
            new DocumentCreatedEvent(), new XObjectAddedEvent(GROUP_REFERENCE),
            new XObjectDeletedEvent(GROUP_REFERENCE), new XObjectUpdatedEvent(GROUP_REFERENCE),
            new XObjectAddedEvent(GLOBALRIGHT_REFERENCE), new XObjectDeletedEvent(GLOBALRIGHT_REFERENCE),
            new XObjectUpdatedEvent(GLOBALRIGHT_REFERENCE), new XObjectAddedEvent(RIGHT_REFERENCE),
            new XObjectDeletedEvent(RIGHT_REFERENCE), new XObjectUpdatedEvent(RIGHT_REFERENCE));
    }

    @Override
    public void onEvent(Event event, Object source, Object data)
    {
        if (event instanceof WikiDeletedEvent) {
            // Invalidate the group cache for the deleted wiki
            this.groupManager.invalidate(new WikiReference(((WikiEvent) event).getWikiId()));
        } else if (event instanceof DocumentCreatedEvent) {
            // Index the new document
            XWikiDocument document = (XWikiDocument) source;
            indexEntity(new DocumentReference(document.getDocumentReference(), document.getRealLocale()), true);
        } else if (event instanceof ApplicationReadyEvent || event instanceof WikiReadyEvent) {
            // Make sure the wiki is indexed at startup
            indexEntity(((XWikiContext) data).getWikiReference(), false);
        } else if (event instanceof XObjectEvent) {
            BaseObjectReference objectReference = (BaseObjectReference) ((XObjectEvent) event).getReference();

            XWikiDocument document = (XWikiDocument) source;
            XWikiDocument oldDocument = document.getOriginalDocument();
            BaseObject newXobject = document.getXObject(objectReference);
            BaseObject oldXobject = oldDocument != null ? oldDocument.getXObject(objectReference) : null;

            if (GROUP_REFERENCE.equals(objectReference)) {
                // It's a group member change

                // Invalidate the groups cache in the wiki if the group is new or deleted
                if ((event instanceof XObjectAddedEvent && document.getXObjects(LOCAL_GROUP_REFERENCE).size() == 1)
                    || (event instanceof XObjectDeletedEvent
                        && document.getXObjects(LOCAL_GROUP_REFERENCE).isEmpty())) {
                    this.groupManager.invalidate(document.getDocumentReference().getWikiReference());
                }

                // Check previous member
                if (oldXobject != null) {
                    checkGroupMember(oldXobject.getStringValue(GROUP_MEMBER), document.getDocumentReference());
                }

                // Check new member
                if (newXobject != null) {
                    checkGroupMember(newXobject.getStringValue(GROUP_MEMBER), document.getDocumentReference());
                }
            } else if (RIGHT_REFERENCE.equals(objectReference)) {
                // It's a local right change
                indexEntity(document.getDocumentReference(), true);
            } else if (GLOBALRIGHT_REFERENCE.equals(objectReference)) {
                // It's a global right change
                if (document.getDocumentReference().getName().equals(SPACEPREFERENCE_NAME)) {
                    // It's a global space right change
                    indexEntity(document.getDocumentReference().getLastSpaceReference(), true);
                } else if (document.getDocumentReference().getLocalDocumentReference()
                    .equals(WIKIPREFERENCE_REFERENCE)) {
                    // It's a global wiki right change
                    indexEntity(document.getDocumentReference().getWikiReference(), true);
                }
            }
        }
    }

    private void checkGroupMember(String member, DocumentReference groupReference)
    {
        DocumentReference memberReference = this.documentResolver.resolve(member, groupReference);
        if (this.groupManager.isGroup(memberReference)) {
            indexGroup(memberReference);
        }
    }

    private void indexEntity(EntityReference reference, boolean force)
    {
        List<String> id = SolrSecurityJobRequest.getIdForEntity(reference);

        if (shouldIndex(id, force)) {
            SolrSecurityJobRequest request = new SolrSecurityJobRequest();

            request.setId(id);

            request.setEntity(reference);

            execute(request);
        }
    }

    private void indexGroup(DocumentReference groupReference)
    {
        SolrSecurityJobRequest request = new SolrSecurityJobRequest();

        request.setId(SolrSecurityJobRequest.getIdForGroup(groupReference));

        request.setGroupReference(groupReference);

        execute(request);
    }

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

    private void execute(SolrSecurityJobRequest request)
    {
        try {
            this.jobs.execute(SolrSecurityJob.JOBTYPE, request);
        } catch (JobException e) {
            this.logger.error("Failed to start the job for request [{}]", request, e);
        }
    }
}
