/*-
 * #%L
 * Alfresco Indexing Trackers
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

package org.alfresco.indexing.tracker;

import org.alfresco.service.namespace.QName;

/**
 * Callback interface for data model operations.
 * Abstracts the dependency on AlfrescoSolrDataModel (Solr-specific singleton).
 * Implemented by alfresco-search module.
 */
public interface DataModelCallback
{
    /** Called after models have been loaded/updated to refresh CMIS dictionary services. */
    void afterInitModels();

    /** Called to remove a model from the data dictionary. */
    void removeModel(QName modelName);
}
