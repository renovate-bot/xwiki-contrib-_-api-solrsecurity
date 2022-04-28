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

import org.xwiki.bridge.event.ApplicationReadyEvent;
import org.xwiki.bridge.event.WikiDeletedEvent;
import org.xwiki.bridge.event.WikiEvent;
import org.xwiki.bridge.event.WikiReadyEvent;
import org.xwiki.component.annotation.Component;
import org.xwiki.model.reference.DocumentReference;
import org.xwiki.model.reference.DocumentReferenceResolver;
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
    private DocumentReferenceResolver<String> documentResolver;

    @Inject
    private SolrSecurityGroupManager groupManager;

    @Inject
    private SolrSecurityDispatcher dispatcher;

    /**
     * The default constructor.
     */
    public SolrSecurityListener()
    {
        super(NAME, new ApplicationReadyEvent(), new WikiReadyEvent(), new WikiDeletedEvent(),
            // A group has been add/deleted or a member has been added/deleted: need to update add or remove this group
            // in the index and the new/old member if it's a group
            new XObjectAddedEvent(GROUP_REFERENCE), new XObjectDeletedEvent(GROUP_REFERENCE),
            new XObjectUpdatedEvent(GROUP_REFERENCE),
            // A global right has been updated: need to update the index of all the children of that entity
            new XObjectAddedEvent(GLOBALRIGHT_REFERENCE), new XObjectDeletedEvent(GLOBALRIGHT_REFERENCE),
            new XObjectUpdatedEvent(GLOBALRIGHT_REFERENCE),
            // A document right has been updated: need to update the index of all the locales of that document
            new XObjectAddedEvent(RIGHT_REFERENCE), new XObjectDeletedEvent(RIGHT_REFERENCE),
            new XObjectUpdatedEvent(RIGHT_REFERENCE));
    }

    @Override
    public void onEvent(Event event, Object source, Object data)
    {
        if (event instanceof WikiDeletedEvent) {
            // Invalidate the group cache for the deleted wiki
            this.groupManager.invalidate(new WikiReference(((WikiEvent) event).getWikiId()));
        } else if (event instanceof ApplicationReadyEvent || event instanceof WikiReadyEvent) {
            // Make sure the wiki is indexed at startup
            this.dispatcher.indexEntity(((XWikiContext) data).getWikiReference(), false);
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
                // We don't indicate a specific locale since the right change affect all locales of the document
                this.dispatcher.indexEntity(document.getDocumentReference(), true);
            } else if (GLOBALRIGHT_REFERENCE.equals(objectReference)) {
                // It's a global right change
                if (document.getDocumentReference().getName().equals(SPACEPREFERENCE_NAME)) {
                    // It's a global space right change
                    this.dispatcher.indexEntity(document.getDocumentReference().getLastSpaceReference(), true);
                } else if (document.getDocumentReference().getLocalDocumentReference()
                    .equals(WIKIPREFERENCE_REFERENCE)) {
                    // It's a global wiki right change
                    this.dispatcher.indexEntity(document.getDocumentReference().getWikiReference(), true);
                }
            }
        }
    }

    private void checkGroupMember(String member, DocumentReference groupReference)
    {
        DocumentReference memberReference = this.documentResolver.resolve(member, groupReference);
        if (this.groupManager.isGroup(memberReference)) {
            this.dispatcher.indexGroup(memberReference);
        }
    }
}
