// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.

suite("test_export_with_hdfs", "p2") {
    // open nereids
    sql """ set enable_nereids_planner=true """
    sql """ set enable_fallback_to_original_planner=false """


    String nameNodeHost = context.config.otherConfigs.get("extHiveHmsHost")
    String hdfsPort = context.config.otherConfigs.get("extHdfsPort")
    String fs = "hdfs://${nameNodeHost}:${hdfsPort}"
    String user_name = context.config.otherConfigs.get("extHiveHmsUser")


    def table_export_name = "test_export_with_hdfs"
    // create table and insert
    sql """ DROP TABLE IF EXISTS ${table_export_name} """
    sql """
    CREATE TABLE IF NOT EXISTS ${table_export_name} (
        `id` int(11) NULL,
        `name` string NULL,
        `age` int(11) NULL
        )
        PARTITION BY RANGE(id)
        (
            PARTITION less_than_20 VALUES LESS THAN ("20"),
            PARTITION between_20_70 VALUES [("20"),("70")),
            PARTITION more_than_70 VALUES LESS THAN ("151")
        )
        DISTRIBUTED BY HASH(id) BUCKETS 3
        PROPERTIES("replication_num" = "1");
    """
    StringBuilder sb = new StringBuilder()
    int i = 1
    for (; i < 10; i ++) {
        sb.append("""
            (${i}, 'ftw-${i}', ${i + 18}),
        """)
    }
    sb.append("""
            (${i}, NULL, NULL)
        """)
    sql """ INSERT INTO ${table_export_name} VALUES
            ${sb.toString()}
        """
    qt_select_export """ SELECT * FROM ${table_export_name} t ORDER BY id; """


    def waiting_export = { export_label ->
        while (true) {
            def res = sql """ show export where label = "${export_label}" """
            logger.info("export state: " + res[0][2])
            if (res[0][2] == "FINISHED") {
                def json = parseJson(res[0][11])
                assert json instanceof List
                assertEquals("1", json.fileNumber[0][0])
                log.info("outfile_path: ${json.url[0][0]}")
                return json.url[0][0];
            } else if (res[0][2] == "CANCELLED") {
                throw new IllegalStateException("""export failed: ${res[0][10]}""")
            } else {
                sleep(5000)
            }
        }
    }

    def outFilePath = """/user/export_test/export/exp_"""

    // 1. csv test
    def test_export = {format, file_suffix, isDelete ->
        def uuid = UUID.randomUUID().toString()
        // exec export
        sql """
            EXPORT TABLE ${table_export_name} TO "${fs}${outFilePath}"
            PROPERTIES(
                "label" = "${uuid}",
                "format" = "${format}",
                "column_separator"=",",
                "delete_existing_files"="${isDelete}"
            )
            with HDFS (
                "fs.defaultFS"="${fs}",
                "hadoop.username" = "${user_name}"
            );
        """

        def outfile_url = waiting_export.call(uuid)

        // check data correctness
        order_qt_select """ select * from hdfs(
                "uri" = "${outfile_url}0.${file_suffix}",
                "fs.defaultFS" = "${fs}",
                "hadoop.username" = "${user_name}",
                "format" = "${format}");
            """
    }

    test_export('csv', 'csv', true);
    test_export('parquet', 'parquet', true);
    test_export('orc', 'orc', true);
    test_export('csv_with_names', 'csv', true);
    test_export('csv_with_names_and_types', 'csv', true);
}
