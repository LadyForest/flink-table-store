/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.flink.table.store.connector;

import org.apache.flink.configuration.ConfigOption;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.core.fs.Path;
import org.apache.flink.table.api.TableException;
import org.apache.flink.table.catalog.CatalogPartitionSpec;
import org.apache.flink.table.catalog.ObjectIdentifier;
import org.apache.flink.table.factories.FactoryUtil;
import org.apache.flink.table.factories.ManagedTableFactory;
import org.apache.flink.table.store.kafka.KafkaLogStoreFactory;
import org.apache.flink.table.store.log.LogStoreTableFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.apache.flink.table.store.connector.TableStoreFactoryOptions.CHANGE_TRACKING;
import static org.apache.flink.table.store.file.FileStoreOptions.BUCKET;
import static org.apache.flink.table.store.file.FileStoreOptions.FILE_PATH;
import static org.apache.flink.table.store.file.FileStoreOptions.MANIFEST_TARGET_FILE_SIZE;
import static org.apache.flink.table.store.file.FileStoreOptions.TABLE_STORE_PREFIX;

/** Default implementation of {@link ManagedTableFactory}. */
public class TableStoreFactory implements ManagedTableFactory {

    @Override
    public Map<String, String> enrichOptions(Context context) {
        Map<String, String> enrichedOptions = new HashMap<>(context.getCatalogTable().getOptions());
        ((Configuration) context.getConfiguration())
                .toMap()
                .forEach(
                        (k, v) -> {
                            if (k.startsWith(TABLE_STORE_PREFIX)) {
                                enrichedOptions.putIfAbsent(
                                        k.substring(TABLE_STORE_PREFIX.length()), v);
                            }
                        });
        return enrichedOptions;
    }

    @Override
    public void onCreateTable(Context context, boolean ignoreIfExists) {
        Map<String, String> enrichedOptions = new HashMap<>(context.getCatalogTable().getOptions());
        Path path = tablePath(enrichedOptions, context.getObjectIdentifier());
        try {
            if (path.getFileSystem().exists(path) && !ignoreIfExists) {
                throw new TableException(
                        String.format(
                                "Failed to create file store path. "
                                        + "Reason: directory %s exists for table %s. "
                                        + "Suggestion: please try `DESCRIBE TABLE %s` to "
                                        + "first check whether table exists in current catalog. "
                                        + "If table exists in catalog, and data files under current path "
                                        + "are valid, please use `CREATE TABLE IF NOT EXISTS` ddl instead. "
                                        + "Otherwise, please choose another table name "
                                        + "or manually delete the current path and try again.",
                                path,
                                context.getObjectIdentifier().asSerializableString(),
                                context.getObjectIdentifier().asSerializableString()));
            }
            path.getFileSystem().mkdirs(path);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

        if (enableChangeTracking(enrichedOptions)) {
            Context logStoreContext =
                    new FactoryUtil.DefaultDynamicTableContext(
                            context.getObjectIdentifier(),
                            context.getCatalogTable().copy(filterLogStoreOptions(enrichedOptions)),
                            context.getEnrichmentOptions(),
                            context.getConfiguration(),
                            context.getClassLoader(),
                            context.isTemporary());
            LogStoreTableFactory.discoverLogStoreFactory(
                            Thread.currentThread().getContextClassLoader(),
                            KafkaLogStoreFactory.IDENTIFIER)
                    .onCreateTable(
                            logStoreContext,
                            Integer.parseInt(
                                    enrichedOptions.getOrDefault(
                                            BUCKET.key(), BUCKET.defaultValue().toString())),
                            ignoreIfExists);
        }
    }

    @Override
    public void onDropTable(Context context, boolean ignoreIfNotExists) {
        Map<String, String> enrichedOptions = context.getCatalogTable().getOptions();
        Path path = tablePath(enrichedOptions, context.getObjectIdentifier());
        try {
            if (path.getFileSystem().exists(path)) {
                path.getFileSystem().delete(path, true);
            } else if (!ignoreIfNotExists) {
                throw new TableException(
                        String.format(
                                "Failed to delete file store path. "
                                        + "Reason: directory %s doesn't exist for table %s. "
                                        + "Suggestion: please try `DROP TABLE IF EXISTS` ddl instead.",
                                path, context.getObjectIdentifier().asSerializableString()));
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        if (enableChangeTracking(enrichedOptions)) {
            Context logStoreContext =
                    new FactoryUtil.DefaultDynamicTableContext(
                            context.getObjectIdentifier(),
                            context.getCatalogTable().copy(filterLogStoreOptions(enrichedOptions)),
                            context.getEnrichmentOptions(),
                            context.getConfiguration(),
                            context.getClassLoader(),
                            context.isTemporary());
            LogStoreTableFactory.discoverLogStoreFactory(
                            Thread.currentThread().getContextClassLoader(),
                            KafkaLogStoreFactory.IDENTIFIER)
                    .onDropTable(logStoreContext, ignoreIfNotExists);
        }
    }

    @Override
    public Map<String, String> onCompactTable(
            Context context, CatalogPartitionSpec catalogPartitionSpec) {
        // TODO: next commit
        return Collections.emptyMap();
    }

    @Override
    public Set<ConfigOption<?>> requiredOptions() {
        return Collections.emptySet();
    }

    @Override
    public Set<ConfigOption<?>> optionalOptions() {
        LogStoreTableFactory logStoreTableFactory =
                LogStoreTableFactory.discoverLogStoreFactory(
                        Thread.currentThread().getContextClassLoader(),
                        KafkaLogStoreFactory.IDENTIFIER);
        Set<ConfigOption<?>> options = new HashSet<>(logStoreTableFactory.optionalOptions());
        options.addAll(logStoreTableFactory.requiredOptions());
        options.add(BUCKET);
        options.add(MANIFEST_TARGET_FILE_SIZE);
        options.add(CHANGE_TRACKING);
        return options;
    }

    // ~ Tools ------------------------------------------------------------------

    private Map<String, String> filterLogStoreOptions(Map<String, String> enrichedOptions) {
        Map<String, String> logStoreOptions = new HashMap<>();
        LogStoreTableFactory logStoreTableFactory =
                LogStoreTableFactory.discoverLogStoreFactory(
                        Thread.currentThread().getContextClassLoader(),
                        KafkaLogStoreFactory.IDENTIFIER);
        Set<String> logStoreOptionKeys =
                Stream.concat(
                                logStoreTableFactory.requiredOptions().stream()
                                        .map(ConfigOption::key),
                                logStoreTableFactory.optionalOptions().stream()
                                        .map(ConfigOption::key))
                        .collect(Collectors.toSet());
        enrichedOptions.forEach(
                (k, v) -> {
                    if (logStoreOptionKeys.contains(k)) {
                        logStoreOptions.put(k, v);
                    }
                });
        return logStoreOptions;
    }

    private static Path tablePath(Map<String, String> options, ObjectIdentifier identifier) {
        return new Path(new Path(options.get(FILE_PATH.key())), identifier.asSummaryString());
    }

    private static boolean enableChangeTracking(Map<String, String> options) {
        return Boolean.parseBoolean(
                options.getOrDefault(
                        CHANGE_TRACKING.key(), CHANGE_TRACKING.defaultValue().toString()));
    }
}