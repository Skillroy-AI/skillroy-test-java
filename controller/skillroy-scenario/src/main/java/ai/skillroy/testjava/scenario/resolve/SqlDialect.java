/*
 * Copyright 2026 Mike Mills.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ai.skillroy.testjava.scenario.resolve;

/**
 * The SQL dialect of a data source named in a {@link SqlAllowList} (phase5-contracts.md 5.2): the dialect in which the
 * controller's guard parses a statement a model wrote, and the one the prompt catalogue tells the model to write.
 * {@link #GENERIC} is the dialect-neutral subset.
 */
public enum SqlDialect {
    /** HyperSQL. */
    HSQLDB,
    /** MySQL. */
    MYSQL,
    /** MariaDB. */
    MARIADB,
    /** PostgreSQL. */
    POSTGRESQL,
    /** IBM Db2. */
    DB2,
    /** Oracle Database. */
    ORACLE,
    /** Microsoft SQL Server. */
    SQLSERVER,
    /** The dialect-neutral subset of SQL. */
    GENERIC
}
