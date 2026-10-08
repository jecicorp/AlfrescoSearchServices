/*-
 * #%L
 * Pristy Indexing Trackers
 * %%
 * Copyright (C) 2026 Jeci SARL - https://jeci.fr
 * %%
 * This file is part of the Pristy software, developed by Jeci SARL.
 * 
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * 
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 * 
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 * #L%
 */
package org.alfresco.indexing.audit;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;
import java.util.Map;

/**
 * The audit message as it travels on Kafka. Mirrors the producer's payload without
 * depending on it; the producer lives in an Alfresco AMP whose dependency tree has no
 * place in this service.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class AuditEventMessage
{
    private String id;
    private Long timestamp;
    private String eventType;
    private String authenticatedUser;
    private String executingUser;
    private String firstName;
    private String lastName;
    private String userEmail;
    private NodeRefDto nodeRef;
    private Long nodeDbId;
    private NodeRefDto parentNodeRef;
    private List<NodeRefDto> ancestorNodeRefs;
    private String nodeType;
    private String nodeTypeTitle;
    private List<String> nodeAspects;
    private String siteId;
    private String path;
    private String shortPath;
    private Boolean file;
    private String fileMimetype;
    private Long fileSize;
    private Map<String, String> properties;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public Long getTimestamp() { return timestamp; }
    public void setTimestamp(Long timestamp) { this.timestamp = timestamp; }

    public String getEventType() { return eventType; }
    public void setEventType(String eventType) { this.eventType = eventType; }

    public String getAuthenticatedUser() { return authenticatedUser; }
    public void setAuthenticatedUser(String authenticatedUser) { this.authenticatedUser = authenticatedUser; }

    public String getExecutingUser() { return executingUser; }
    public void setExecutingUser(String executingUser) { this.executingUser = executingUser; }

    public String getFirstName() { return firstName; }
    public void setFirstName(String firstName) { this.firstName = firstName; }

    public String getLastName() { return lastName; }
    public void setLastName(String lastName) { this.lastName = lastName; }

    public String getUserEmail() { return userEmail; }
    public void setUserEmail(String userEmail) { this.userEmail = userEmail; }

    public NodeRefDto getNodeRef() { return nodeRef; }
    public void setNodeRef(NodeRefDto nodeRef) { this.nodeRef = nodeRef; }

    public Long getNodeDbId() { return nodeDbId; }
    public void setNodeDbId(Long nodeDbId) { this.nodeDbId = nodeDbId; }

    public NodeRefDto getParentNodeRef() { return parentNodeRef; }
    public void setParentNodeRef(NodeRefDto parentNodeRef) { this.parentNodeRef = parentNodeRef; }

    public List<NodeRefDto> getAncestorNodeRefs() { return ancestorNodeRefs; }
    public void setAncestorNodeRefs(List<NodeRefDto> ancestorNodeRefs) { this.ancestorNodeRefs = ancestorNodeRefs; }

    public String getNodeType() { return nodeType; }
    public void setNodeType(String nodeType) { this.nodeType = nodeType; }

    public String getNodeTypeTitle() { return nodeTypeTitle; }
    public void setNodeTypeTitle(String nodeTypeTitle) { this.nodeTypeTitle = nodeTypeTitle; }

    public List<String> getNodeAspects() { return nodeAspects; }
    public void setNodeAspects(List<String> nodeAspects) { this.nodeAspects = nodeAspects; }

    public String getSiteId() { return siteId; }
    public void setSiteId(String siteId) { this.siteId = siteId; }

    public String getPath() { return path; }
    public void setPath(String path) { this.path = path; }

    public String getShortPath() { return shortPath; }
    public void setShortPath(String shortPath) { this.shortPath = shortPath; }

    public Boolean getFile() { return file; }
    public void setFile(Boolean file) { this.file = file; }

    public String getFileMimetype() { return fileMimetype; }
    public void setFileMimetype(String fileMimetype) { this.fileMimetype = fileMimetype; }

    public Long getFileSize() { return fileSize; }
    public void setFileSize(Long fileSize) { this.fileSize = fileSize; }

    public Map<String, String> getProperties() { return properties; }
    public void setProperties(Map<String, String> properties) { this.properties = properties; }

    /** A node reference as the producer serialises it: a nested object, never a string. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class NodeRefDto
    {
        private StoreRefDto storeRef;
        private String id;

        public StoreRefDto getStoreRef() { return storeRef; }
        public void setStoreRef(StoreRefDto storeRef) { this.storeRef = storeRef; }

        public String getId() { return id; }
        public void setId(String id) { this.id = id; }

        /** @return the canonical {@code protocol://identifier/id} form, or null when incomplete */
        public String toNodeRef()
        {
            if (storeRef == null || storeRef.getProtocol() == null || id == null)
            {
                return null;
            }
            return storeRef.getProtocol() + "://" + storeRef.getIdentifier() + "/" + id;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class StoreRefDto
    {
        private String protocol;
        private String identifier;

        public String getProtocol() { return protocol; }
        public void setProtocol(String protocol) { this.protocol = protocol; }

        public String getIdentifier() { return identifier; }
        public void setIdentifier(String identifier) { this.identifier = identifier; }
    }
}
