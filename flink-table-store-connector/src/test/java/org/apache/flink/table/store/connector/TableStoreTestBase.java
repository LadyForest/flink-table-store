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

import org.apache.flink.api.common.RuntimeExecutionMode;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.table.api.Schema;
import org.apache.flink.table.api.bridge.java.StreamTableEnvironment;
import org.apache.flink.table.api.internal.TableEnvironmentImpl;
import org.apache.flink.table.catalog.CatalogTable;
import org.apache.flink.table.catalog.Column;
import org.apache.flink.table.catalog.GenericInMemoryCatalog;
import org.apache.flink.table.catalog.ObjectIdentifier;
import org.apache.flink.table.catalog.ResolvedCatalogTable;
import org.apache.flink.table.catalog.ResolvedSchema;
import org.apache.flink.table.catalog.UniqueConstraint;
import org.apache.flink.table.store.kafka.KafkaTableTestBase;
import org.apache.flink.table.types.DataType;
import org.apache.flink.table.types.logical.RowType;
import org.apache.flink.table.types.utils.TypeConversions;
import org.apache.flink.types.Row;

import org.apache.commons.io.FileUtils;
import org.junit.Before;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.apache.flink.table.store.connector.TableStoreFactoryOptions.CHANGE_TRACKING;
import static org.apache.flink.table.store.file.FileStoreOptions.FILE_PATH;
import static org.apache.flink.table.store.file.FileStoreOptions.TABLE_STORE_PREFIX;
import static org.apache.flink.table.store.kafka.KafkaLogOptions.BOOTSTRAP_SERVERS;
import static org.apache.flink.table.store.log.LogOptions.LOG_PREFIX;

/** End-to-end test base for table store. */
public abstract class TableStoreTestBase extends KafkaTableTestBase {

    public static final String CURRENT_CATALOG = "catalog";
    public static final String CURRENT_DATABASE = "database";

    protected final RuntimeExecutionMode executionMode;
    protected final ObjectIdentifier tableIdentifier;
    protected final boolean enableChangeTracking;
    protected final ExpectedResult expectedResult;

    protected String rootPath;

    public TableStoreTestBase(
            RuntimeExecutionMode executionMode,
            String tableName,
            boolean enableChangeTracking,
            ExpectedResult expectedResult) {
        this.executionMode = executionMode;
        this.tableIdentifier = ObjectIdentifier.of(CURRENT_CATALOG, CURRENT_DATABASE, tableName);
        this.enableChangeTracking = enableChangeTracking;
        this.expectedResult = expectedResult;
    }

    protected abstract void prepareEnv();

    @Override
    @Before
    public void setup() {
        super.setup();
        env.setRuntimeMode(executionMode);
        if (executionMode == RuntimeExecutionMode.STREAMING) {
            env.enableCheckpointing(100);
        }
        tEnv = StreamTableEnvironment.create(env);
        ((TableEnvironmentImpl) tEnv)
                .getCatalogManager()
                .registerCatalog(
                        CURRENT_CATALOG,
                        new GenericInMemoryCatalog(CURRENT_CATALOG, CURRENT_DATABASE));
        tEnv.useCatalog(CURRENT_CATALOG);
        try {
            rootPath = TEMPORARY_FOLDER.newFolder().getPath();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        prepareSessionContext();
        prepareEnv();
    }

    protected void prepareSessionContext() {
        Configuration configuration = tEnv.getConfig().getConfiguration();
        configuration.setString(TABLE_STORE_PREFIX + FILE_PATH.key(), rootPath);
        configuration.setString(
                TABLE_STORE_PREFIX + LOG_PREFIX + BOOTSTRAP_SERVERS.key(), getBootstrapServers());
        configuration.setBoolean(TABLE_STORE_PREFIX + CHANGE_TRACKING.key(), enableChangeTracking);
    }

    protected static ResolvedCatalogTable createResolvedTable(
            Map<String, String> options, RowType rowType, List<String> partitionKeys, int[] pk) {
        List<String> fieldNames = rowType.getFieldNames();
        List<DataType> fieldDataTypes =
                rowType.getChildren().stream()
                        .map(TypeConversions::fromLogicalToDataType)
                        .collect(Collectors.toList());
        CatalogTable origin =
                CatalogTable.of(
                        Schema.newBuilder().fromFields(fieldNames, fieldDataTypes).build(),
                        "a comment",
                        partitionKeys,
                        options);
        List<Column> resolvedColumns =
                IntStream.range(0, fieldNames.size())
                        .mapToObj(i -> Column.physical(fieldNames.get(i), fieldDataTypes.get(i)))
                        .collect(Collectors.toList());
        UniqueConstraint constraint = null;
        if (pk.length > 0) {
            List<String> pkNames =
                    Arrays.stream(pk).mapToObj(fieldNames::get).collect(Collectors.toList());
            constraint = UniqueConstraint.primaryKey("pk", pkNames);
        }
        return new ResolvedCatalogTable(
                origin, new ResolvedSchema(resolvedColumns, Collections.emptyList(), constraint));
    }

    protected void deleteTablePath() {
        try {
            FileUtils.deleteDirectory(
                    Paths.get(rootPath, getRelativeFileStoreTablePath(tableIdentifier)).toFile());
        } catch (IOException ignored) {
            // ignored
        }
    }

    protected static String getRelativeFileStoreTablePath(ObjectIdentifier tableIdentifier) {
        return String.format(
                "root/%s.catalog/%s.db/%s",
                tableIdentifier.getCatalogName(),
                tableIdentifier.getDatabaseName(),
                tableIdentifier.getObjectName());
    }

    /** Expected result wrapper. */
    protected static class ExpectedResult {
        protected boolean success;
        protected List<Row> expectedRecords;
        protected Class<? extends Throwable> expectedType;
        protected String expectedMessage;

        ExpectedResult success(boolean success) {
            this.success = success;
            return this;
        }

        ExpectedResult expectedRecords(List<Row> expectedRecords) {
            this.expectedRecords = expectedRecords;
            return this;
        }

        ExpectedResult expectedType(Class<? extends Throwable> exceptionClazz) {
            this.expectedType = exceptionClazz;
            return this;
        }

        ExpectedResult expectedMessage(String exceptionMessage) {
            this.expectedMessage = exceptionMessage;
            return this;
        }

        @Override
        public String toString() {
            return "ExpectedResult{"
                    + "success="
                    + success
                    + ", expectedRecords="
                    + expectedRecords
                    + ", expectedType="
                    + expectedType
                    + ", expectedMessage='"
                    + expectedMessage
                    + '\''
                    + '}';
        }
    }
}
