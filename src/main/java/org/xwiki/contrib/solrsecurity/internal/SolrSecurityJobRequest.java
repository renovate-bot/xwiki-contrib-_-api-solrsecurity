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

import org.xwiki.job.AbstractRequest;
import org.xwiki.job.Request;
import org.xwiki.model.reference.DocumentReference;
import org.xwiki.model.reference.EntityReference;

/**
 * The request used as input of the {@link SolrSecurityJob}.
 * 
 * @version $Id: 1ecf4cb0257b062056b663fe107ee4c25fbd31f1 $
 */
public class SolrSecurityJobRequest extends AbstractRequest
{
    private EntityReference entity;

    private DocumentReference groupReference;

    /**
     * The default constructor.
     */
    public SolrSecurityJobRequest()
    {
        setVerbose(false);
        setStatusLogIsolated(false);
    }

    /**
     * @param request the request to copy
     */
    public SolrSecurityJobRequest(Request request)
    {
        super(request);
    }

    /**
     * @return the entity (and its children) to index.
     */
    public EntityReference getEntity()
    {
        return this.entity;
    }

    /**
     * @param entity the entity (and its children) to index.
     */
    public void setEntity(EntityReference entity)
    {
        this.entity = entity;
    }

    /**
     * @return the group (and its children) to index.
     */
    public DocumentReference getGroupReference()
    {
        return this.groupReference;
    }

    /**
     * @param groupReference the group (and its children) to index.
     */
    public void setGroupReference(DocumentReference groupReference)
    {
        this.groupReference = groupReference;
    }
}
