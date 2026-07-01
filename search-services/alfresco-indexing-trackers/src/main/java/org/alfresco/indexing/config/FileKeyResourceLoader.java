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
package org.alfresco.indexing.config;

import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;
import org.alfresco.encryption.KeyResourceLoader;

/**
 * Loads keystores/truststores from the local filesystem.
 *
 * <p>Solr's {@code SolrKeyResourceLoader} is unavailable in the standalone
 * trackers process, so this class provides a minimal filesystem-based
 * implementation of {@link KeyResourceLoader} for use when building an
 * {@code AlfrescoHttpClient} in HTTPS/mTLS mode.</p>
 */
public class FileKeyResourceLoader implements KeyResourceLoader
{
    /**
     * Opens the keystore at {@code location} as a plain filesystem path.
     *
     * @param location absolute or relative path to the keystore file
     * @return an open {@link InputStream} for the file; caller must close it
     * @throws FileNotFoundException if the file does not exist
     */
    @Override
    public InputStream getKeyStore(String location) throws FileNotFoundException
    {
        return new FileInputStream(location);
    }

    /**
     * Returns an empty {@link Properties} object.
     *
     * <p>The standalone trackers process does not use metadata side-car files;
     * all SSL parameters are supplied via Spring Boot configuration properties.
     * This stub satisfies the interface contract and allows the caller to
     * operate without null-checking the return value.</p>
     *
     * @param location path to the metadata file (ignored)
     * @return an empty, non-null {@link Properties} instance
     */
    @Override
    public Properties loadKeyMetaData(String location) throws IOException
    {
        return new Properties();
    }
}
