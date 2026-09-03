/*
 * #%L
 * Alfresco Search Services
 * %%
 * Copyright (C) 2005 - 2024 Alfresco Software Limited
 * %%
 * This file is part of the Alfresco software. 
 * If the software was purchased under a paid Alfresco license, the terms of 
 * the paid license agreement will prevail.  Otherwise, the software is 
 * provided under the following open source license terms:
 * 
 * Alfresco is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * 
 * Alfresco is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 * 
 * You should have received a copy of the GNU Lesser General Public License
 * along with Alfresco. If not, see <http://www.gnu.org/licenses/>.
 * #L%
 */

package org.alfresco;

import org.junit.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static java.util.stream.Collectors.toList;
import static java.util.stream.Collectors.toSet;
import static org.junit.Assert.assertEquals;

/**
 * Integration tests of the declared third party licenses.
 * <p>
 * The distribution ships two license inventories, and this test guards the seam between them:
 * every jar the build adds to the Solr webapp must appear in one of them. Apache's own
 * {@code solr/licenses} covers the jars Solr itself ships, and is not checked here.
 * <p>
 * See {@code docs/third-party-licenses.md}.
 */
public class ThirdPartyLicensesIT
{
    /** Artifacts produced by the fork or by Alfresco, which need no third-party declaration. */
    private static final List<String> OWN_ARTIFACT_PREFIXES =
                List.of("alfresco-", "pristy-", "spring-surf-");

    /** {@code (License) Name (groupId:artifactId:version - url)}, as license-maven-plugin writes it. */
    private static final Pattern INVENTORY_ENTRY =
                Pattern.compile("\\(([^:()]+):([^:()]+):([^ )]+) -");

    @Test
    public void everyShippedJarHasADeclaredLicense() throws Exception
    {
        Path target = Paths.get(getClass().getProtectionDomain().getCodeSource().getLocation().toURI()).getParent();

        Set<String> generated = generatedInventory(target.resolve("pristy-search/THIRD-PARTY.txt"));
        Set<String> handDeclared = handDeclaredInventory(target.resolve("classes/licenses/notice.txt"));

        List<String> undeclared = shippedJars(target.resolve("solr-libs/libs")).stream()
                    .filter(jar -> !handDeclared.contains(jar))
                    .filter(jar -> generated.stream().noneMatch(jar::startsWith))
                    .sorted()
                    .collect(toList());

        assertEquals("Jars added to the Solr webapp with no declared license. Either the dependency "
                    + "is new (regenerate nothing -- license-maven-plugin picks it up on the next "
                    + "build) or it is copied in by the packaging module, in which case declare it "
                    + "in licenses/notice.txt.", List.of(), undeclared);
    }

    @Test
    public void noStaleHandDeclaration() throws Exception
    {
        Path target = Paths.get(getClass().getProtectionDomain().getCodeSource().getLocation().toURI()).getParent();

        Set<String> shipped = shippedJars(target.resolve("solr-libs/libs"));
        List<String> stale = handDeclaredInventory(target.resolve("classes/licenses/notice.txt")).stream()
                    .filter(jar -> !shipped.contains(jar))
                    .sorted()
                    .collect(toList());

        assertEquals("Jars declared in licenses/notice.txt that the build no longer adds. Remove "
                    + "them, or move the declaration if the artifact changed version.",
                    List.of(), stale);
    }

    /** The jars the build adds to the Solr webapp, excluding artifacts of the fork itself. */
    private static Set<String> shippedJars(Path libs) throws IOException
    {
        try (Stream<Path> files = Files.list(libs))
        {
            return files.map(path -> path.getFileName().toString())
                        .filter(name -> name.endsWith(".jar"))
                        .filter(name -> OWN_ARTIFACT_PREFIXES.stream().noneMatch(name::startsWith))
                        .collect(toSet());
        }
    }

    /**
     * The {@code artifactId-version} prefixes of the inventory license-maven-plugin generates for
     * {@code pristy-search}. Prefixes rather than file names, so that a jar carrying a classifier
     * ({@code netty-transport-native-epoll-4.2.6.Final-linux-x86_64.jar}) still matches.
     */
    private static Set<String> generatedInventory(Path thirdParty) throws IOException
    {
        Matcher matcher = INVENTORY_ENTRY.matcher(Files.readString(thirdParty));
        return matcher.results()
                    .map(result -> result.group(2) + "-" + result.group(3))
                    .collect(toSet());
    }

    /** The jar names declared by hand in notice.txt, for what the packaging module copies in. */
    private static Set<String> handDeclaredInventory(Path notice) throws IOException
    {
        return Files.readAllLines(notice).stream()
                    .map(line -> line.split("\\s+")[0])
                    .filter(name -> name.endsWith(".jar"))
                    .collect(toSet());
    }
}
