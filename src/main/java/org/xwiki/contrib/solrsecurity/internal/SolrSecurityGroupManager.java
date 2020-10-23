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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import javax.inject.Inject;
import javax.inject.Provider;
import javax.inject.Singleton;

import org.xwiki.component.annotation.Component;
import org.xwiki.model.internal.reference.EntityReferenceFactory;
import org.xwiki.model.reference.DocumentReference;
import org.xwiki.model.reference.DocumentReferenceResolver;
import org.xwiki.model.reference.WikiReference;
import org.xwiki.user.group.GroupException;
import org.xwiki.user.group.GroupManager;
import org.xwiki.wiki.descriptor.WikiDescriptorManager;

import com.xpn.xwiki.XWikiContext;
import com.xpn.xwiki.XWikiException;
import com.xpn.xwiki.user.api.XWikiGroupService;

/**
 * Cache and handle groups and users for the Solr secure module.
 * 
 * @version $Id: 326c60aae615460ab14ee84fde06ba5588480c5a $
 */
@Component(roles = SolrSecurityGroupManager.class)
@Singleton
public class SolrSecurityGroupManager
{
    private final Map<WikiReference, Set<DocumentReference>> cachedGroups = new ConcurrentHashMap<>();

    @Inject
    private WikiDescriptorManager wikis;

    @Inject
    private DocumentReferenceResolver<String> documentResolver;

    @Inject
    private EntityReferenceFactory factory;

    @Inject
    private GroupManager groupManager;

    @Inject
    private Provider<XWikiContext> xcontextProvider;

    /**
     * Remove a wiki from the cache of groups.
     * 
     * @param wiki the reference of the wiki to remove from the cache
     */
    public void invalidate(WikiReference wiki)
    {
        this.cachedGroups.remove(wiki);
        this.cachedGroups.remove(new WikiReference(this.wikis.getMainWikiId()));
    }

    /**
     * @param entity the entity to check
     * @return true if the entity is a group
     */
    public boolean isGroup(DocumentReference entity)
    {
        try {
            return getGroups(entity.getWikiReference()).contains(entity);
        } catch (XWikiException e) {
            return false;
        }
    }

    /**
     * @param group the top level group
     * @return the group and its children groups
     * @throws GroupException when failing
     * @throws XWikiException when failing
     */
    public Set<DocumentReference> getGroups(DocumentReference group) throws GroupException, XWikiException
    {
        Collection<DocumentReference> members = this.groupManager.getMembers(group, true);

        Set<DocumentReference> groups = new HashSet<>();
        for (DocumentReference member : members) {
            if (getGroups(member.getWikiReference()).contains(member)) {
                groups.add(member);
            }
        }

        return groups;
    }

    /**
     * @param wiki the reference of the wiki
     * @return the groups available in the passed wiki
     * @throws XWikiException when failing
     */
    public Set<DocumentReference> getGroups(WikiReference wiki) throws XWikiException
    {
        Set<DocumentReference> groups = this.cachedGroups.get(wiki);

        if (groups != null) {
            return groups;
        }

        groups = new HashSet<>();
        if (!this.wikis.getMainWikiId().equals(wiki.getName())) {
            groups.addAll(getGroups(new WikiReference(this.wikis.getMainWikiId())));
        }

        groups.addAll(loadGroups(wiki));

        this.cachedGroups.put(this.factory.getReference(wiki), groups);

        return groups;
    }

    private Collection<DocumentReference> loadGroups(WikiReference wiki) throws XWikiException
    {
        XWikiContext xcontext = this.xcontextProvider.get();

        XWikiGroupService groupService = xcontext.getWiki().getGroupService(xcontext);

        WikiReference currentWikiReference = xcontext.getWikiReference();
        try {
            xcontext.setWikiReference(wiki);

            List<String> groupNames = (List) groupService.getAllMatchedGroups(null, false, 0, 0, null, xcontext);

            List<DocumentReference> groups = new ArrayList<>(groupNames.size());
            for (String groupName : groupNames) {
                groups.add(this.factory.getReference(this.documentResolver.resolve(groupName, wiki)));
            }

            return groups;
        } finally {
            xcontext.setWikiReference(currentWikiReference);
        }
    }
}
