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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.Properties;
import org.junit.jupiter.api.Test;

class FileKeyResourceLoaderTest
{
    @Test
    void readsKeyStoreFromFilesystem() throws Exception
    {
        // Write known bytes to a temporary file
        byte[] expected = {1, 2, 3, 4, 5};
        File tmp = Files.createTempFile("ks", ".p12").toFile();
        tmp.deleteOnExit();
        Files.write(tmp.toPath(), expected);

        // Read the file via the loader and verify bytes match
        FileKeyResourceLoader loader = new FileKeyResourceLoader();
        try (InputStream in = loader.getKeyStore(tmp.getAbsolutePath()))
        {
            byte[] actual = in.readAllBytes();
            assertArrayEquals(expected, actual);
        }
    }

    @Test
    void throwsFileNotFoundForMissingFile()
    {
        FileKeyResourceLoader loader = new FileKeyResourceLoader();
        assertThrows(FileNotFoundException.class,
            () -> loader.getKeyStore("/nonexistent/path/nope.p12"));
    }

    @Test
    void loadsKeyMetaDataFromPropertiesFile() throws Exception
    {
        // Write a minimal password-properties file to a temp location.
        Properties written = new Properties();
        written.setProperty("aliases", "myalias");
        written.setProperty("keystore.password", "secret");
        written.setProperty("myalias.password", "secret");

        File tmp = Files.createTempFile("ksmeta", ".properties").toFile();
        tmp.deleteOnExit();
        try (FileOutputStream fos = new FileOutputStream(tmp))
        {
            written.store(fos, null);
        }

        // Load via the FileKeyResourceLoader and verify the content is preserved.
        FileKeyResourceLoader loader = new FileKeyResourceLoader();
        Properties loaded = loader.loadKeyMetaData(tmp.getAbsolutePath());

        assertNotNull(loaded);
        assertEquals("myalias", loaded.getProperty("aliases"));
        assertEquals("secret", loaded.getProperty("keystore.password"));
        assertEquals("secret", loaded.getProperty("myalias.password"));
    }

    @Test
    void returnsEmptyPropertiesForNullLocation() throws Exception
    {
        FileKeyResourceLoader loader = new FileKeyResourceLoader();
        Properties result = loader.loadKeyMetaData(null);
        assertNotNull(result);
        assertTrue(result.isEmpty(), "Expected empty Properties for null location");
    }

    @Test
    void returnsEmptyPropertiesForBlankLocation() throws Exception
    {
        FileKeyResourceLoader loader = new FileKeyResourceLoader();
        Properties result = loader.loadKeyMetaData("   ");
        assertNotNull(result);
        assertTrue(result.isEmpty(), "Expected empty Properties for blank location");
    }
}
