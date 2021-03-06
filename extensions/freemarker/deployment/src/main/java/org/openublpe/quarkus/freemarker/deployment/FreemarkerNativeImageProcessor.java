/**
 * Copyright 2019 Project OpenUBL, Inc. and/or its affiliates
 * and other contributors as indicated by the @author tags.
 *
 * Licensed under the Eclipse Public License - v 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.openublpe.quarkus.freemarker.deployment;

import io.quarkus.deployment.annotations.BuildProducer;
import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.builditem.GeneratedResourceBuildItem;
import io.quarkus.deployment.builditem.nativeimage.NativeImageResourceBuildItem;
import org.jboss.logging.Logger;
import org.openublpe.quarkus.freemarker.FreemarkerBuildConfig;
import org.openublpe.quarkus.freemarker.QuarkusPathLocationScanner;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

class FreemarkerNativeImageProcessor {

    private static final String CLASSPATH_APPLICATION_MIGRATIONS_PROTOCOL = "classpath";
    private static final String JAR_APPLICATION_MIGRATIONS_PROTOCOL = "jar";
    private static final String FILE_APPLICATION_MIGRATIONS_PROTOCOL = "file";

    private static final Logger LOGGER = Logger.getLogger(FreemarkerNativeImageProcessor.class);

    @BuildStep
    void build(BuildProducer<NativeImageResourceBuildItem> resourceProducer,
               BuildProducer<GeneratedResourceBuildItem> generatedResourceProducer,
               FreemarkerBuildConfig freemarkerBuildConfig) throws IOException, URISyntaxException {
        registerNativeImageResources(resourceProducer, generatedResourceProducer, freemarkerBuildConfig);
    }

    private void registerNativeImageResources(BuildProducer<NativeImageResourceBuildItem> resource,
                                              BuildProducer<GeneratedResourceBuildItem> generatedResourceProducer,
                                              FreemarkerBuildConfig freemarkerBuildConfig)
            throws IOException, URISyntaxException {
        final List<String> nativeResources = new ArrayList<>();
        List<String> applicationMigrations = discoverFreemarkerTemplates(freemarkerBuildConfig);
        nativeResources.addAll(applicationMigrations);
        // Store freemarker templates in a generated resource that will be accessed later by the Quarkus-Freemarker path scanner
        String resourcesList = applicationMigrations
                .stream()
                .collect(Collectors.joining("\n", "", "\n"));
        generatedResourceProducer.produce(
                new GeneratedResourceBuildItem(
                        QuarkusPathLocationScanner.MIGRATIONS_LIST_FILE,
                        resourcesList.getBytes(StandardCharsets.UTF_8)));
        nativeResources.add(QuarkusPathLocationScanner.MIGRATIONS_LIST_FILE);
        resource.produce(new NativeImageResourceBuildItem(nativeResources.toArray(new String[0])));
    }

    private List<String> discoverFreemarkerTemplates(FreemarkerBuildConfig freemarkerBuildConfig)
            throws IOException, URISyntaxException {
        List<String> resources = new ArrayList<>();
        try {
            List<String> locations = new ArrayList<>(freemarkerBuildConfig.locations);
            if (locations.isEmpty()) {
                locations.add("freemarker/templates");
            }
            // Locations can be a comma separated list
            for (String location : locations) {
                // Strip any 'classpath:' protocol prefixes because they are assumed
                // but not recognized by ClassLoader.getResources()
                if (location != null && location.startsWith(CLASSPATH_APPLICATION_MIGRATIONS_PROTOCOL + ':')) {
                    location = location.substring(CLASSPATH_APPLICATION_MIGRATIONS_PROTOCOL.length() + 1);
                }
                Enumeration<URL> templates = Thread.currentThread().getContextClassLoader().getResources(location);
                while (templates.hasMoreElements()) {
                    URL path = templates.nextElement();
                    LOGGER.infov("Adding application freemarker templates in path ''{0}'' using protocol ''{1}''", path.getPath(),
                            path.getProtocol());
                    final Set<String> freemarkerTemplates;
                    if (JAR_APPLICATION_MIGRATIONS_PROTOCOL.equals(path.getProtocol())) {
                        try (final FileSystem fileSystem = initFileSystem(path.toURI())) {
                            freemarkerTemplates = getFreemarkerTemplatesFromPath(location, path);
                        }
                    } else if (FILE_APPLICATION_MIGRATIONS_PROTOCOL.equals(path.getProtocol())) {
                        freemarkerTemplates = getFreemarkerTemplatesFromPath(location, path);
                    } else {
                        LOGGER.warnv(
                                "Unsupported URL protocol ''{0}'' for path ''{1}''. Freemarker files will not be discovered.",
                                path.getProtocol(), path.getPath());
                        freemarkerTemplates = null;
                    }
                    if (freemarkerTemplates != null) {
                        resources.addAll(freemarkerTemplates);
                    }
                }
            }
            return resources;
        } catch (IOException | URISyntaxException e) {
            throw e;
        }
    }

    private Set<String> getFreemarkerTemplatesFromPath(final String location, final URL path)
            throws IOException, URISyntaxException {
        try (final Stream<Path> pathStream = Files.walk(Paths.get(path.toURI()))) {
            return pathStream.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".ftl"))
                    .map(it -> {
                        String file = it.toString();
                        int indexOf = file.lastIndexOf(location);
                        String substring = file.substring(indexOf, file.length());
                        return Paths.get(substring).toString();
                    })
                    .peek(it -> LOGGER.debug("Discovered: " + it))
                    .collect(Collectors.toSet());
        }
    }

    private FileSystem initFileSystem(final URI uri) throws IOException {
        final Map<String, String> env = new HashMap<>();
        env.put("create", "true");
        return FileSystems.newFileSystem(uri, env);
    }
}
