/*
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
package com.facebook.presto.iceberg.hadoop;

import com.facebook.presto.iceberg.TestIcebergDistributedQueries;

import static com.facebook.presto.iceberg.CatalogType.HADOOP;

public class TestIcebergHadoopCatalogDistributedQueries
        extends TestIcebergDistributedQueries
{
    public TestIcebergHadoopCatalogDistributedQueries()
    {
        super(HADOOP);
    }

    protected boolean supportsViews()
    {
        return false;
    }

    @Override
    public void testRenameTable()
    {
        // Rename table are not supported by hadoop catalog
    }
}
