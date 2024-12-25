# Do not edit. rules_scala3 autogenerates this file
def _jar_artifact_impl(ctx):
    jar_name = "%s.jar" % ctx.name
    ctx.download(
        output = ctx.path("jar/%s" % jar_name),
        url = ctx.attr.urls,
        executable = False,
    )
    build_file_contents = """
package(default_visibility = ['//visibility:public'])
filegroup(
    name = 'jar',
    srcs = ['{jar_name}'],
    visibility = ['//visibility:public'],
)
alias(
    name = "file",
    actual = ":jar",
    visibility = ["//visibility:public"],
)\n""".format(artifact = ctx.attr.artifact, jar_name = jar_name)
    ctx.file(ctx.path("jar/BUILD"), build_file_contents, False)
    return None

jar_artifact = repository_rule(
    attrs = {
        "artifact": attr.string(mandatory = True),
        "urls": attr.string_list(mandatory = True),
    },
    implementation = _jar_artifact_impl,
)

def jar_artifact_callback(hash):
    jar_artifact(
        artifact = hash["artifact"],
        name = hash["name"],
        urls = [hash["url"]],
    )
    native.bind(name = hash["bind"], actual = hash["actual"])

def list_dependencies():
    return [
        {"artifact":"com.eed3si9n:shaded-jawn-parser_3:1.3.2","url":"https://repo1.maven.org/maven2/com/eed3si9n/shaded-jawn-parser_3/1.3.2/shaded-jawn-parser_3-1.3.2.jar","name":"com_eed3si9n_shaded_jawn_parser_3","actual":"@com_eed3si9n_shaded_jawn_parser_3//jar","bind": "jar/com/eed3si9n/shaded_jawn_parser_3"},
        {"artifact":"com.eed3si9n:shaded-scalajson_3:1.0.0-M4","url":"https://repo1.maven.org/maven2/com/eed3si9n/shaded-scalajson_3/1.0.0-M4/shaded-scalajson_3-1.0.0-M4.jar","name":"com_eed3si9n_shaded_scalajson_3","actual":"@com_eed3si9n_shaded_scalajson_3//jar","bind": "jar/com/eed3si9n/shaded_scalajson_3"},
        {"artifact":"com.eed3si9n:sjson-new-core_3:0.14.0-M1","url":"https://repo1.maven.org/maven2/com/eed3si9n/sjson-new-core_3/0.14.0-M1/sjson-new-core_3-0.14.0-M1.jar","name":"com_eed3si9n_sjson_new_core_3","actual":"@com_eed3si9n_sjson_new_core_3//jar","bind": "jar/com/eed3si9n/sjson_new_core_3"},
        {"artifact":"com.eed3si9n:sjson-new-scalajson_3:0.14.0-M1","url":"https://repo1.maven.org/maven2/com/eed3si9n/sjson-new-scalajson_3/0.14.0-M1/sjson-new-scalajson_3-0.14.0-M1.jar","name":"com_eed3si9n_sjson_new_scalajson_3","actual":"@com_eed3si9n_sjson_new_scalajson_3//jar","bind": "jar/com/eed3si9n/sjson_new_scalajson_3"},
        {"artifact":"com.lmax:disruptor:3.4.2","url":"https://repo1.maven.org/maven2/com/lmax/disruptor/3.4.2/disruptor-3.4.2.jar","name":"com_lmax_disruptor","actual":"@com_lmax_disruptor//jar","bind": "jar/com/lmax/disruptor"},
        {"artifact":"com.swoval:file-tree-views:2.1.12","url":"https://repo1.maven.org/maven2/com/swoval/file-tree-views/2.1.12/file-tree-views-2.1.12.jar","name":"com_swoval_file_tree_views","actual":"@com_swoval_file_tree_views//jar","bind": "jar/com/swoval/file_tree_views"},
        {"artifact":"junit:junit:4.13.2","url":"https://repo1.maven.org/maven2/junit/junit/4.13.2/junit-4.13.2.jar","name":"junit_junit","actual":"@junit_junit//jar","bind": "jar/junit/junit"},
        {"artifact":"net.java.dev.jna:jna:5.13.0","url":"https://repo1.maven.org/maven2/net/java/dev/jna/jna/5.13.0/jna-5.13.0.jar","name":"net_java_dev_jna_jna","actual":"@net_java_dev_jna_jna//jar","bind": "jar/net/java/dev/jna/jna"},
        {"artifact":"net.java.dev.jna:jna-platform:5.13.0","url":"https://repo1.maven.org/maven2/net/java/dev/jna/jna-platform/5.13.0/jna-platform-5.13.0.jar","name":"net_java_dev_jna_jna_platform","actual":"@net_java_dev_jna_jna_platform//jar","bind": "jar/net/java/dev/jna/jna_platform"},
        {"artifact":"net.openhft:zero-allocation-hashing:0.16","url":"https://repo1.maven.org/maven2/net/openhft/zero-allocation-hashing/0.16/zero-allocation-hashing-0.16.jar","name":"net_openhft_zero_allocation_hashing","actual":"@net_openhft_zero_allocation_hashing//jar","bind": "jar/net/openhft/zero_allocation_hashing"},
        {"artifact":"org.apache.logging.log4j:log4j-api:2.17.1","url":"https://repo1.maven.org/maven2/org/apache/logging/log4j/log4j-api/2.17.1/log4j-api-2.17.1.jar","name":"org_apache_logging_log4j_log4j_api","actual":"@org_apache_logging_log4j_log4j_api//jar","bind": "jar/org/apache/logging/log4j/log4j_api"},
        {"artifact":"org.apache.logging.log4j:log4j-core:2.17.1","url":"https://repo1.maven.org/maven2/org/apache/logging/log4j/log4j-core/2.17.1/log4j-core-2.17.1.jar","name":"org_apache_logging_log4j_log4j_core","actual":"@org_apache_logging_log4j_log4j_core//jar","bind": "jar/org/apache/logging/log4j/log4j_core"},
        {"artifact":"org.fusesource.jansi:jansi:2.4.1","url":"https://repo1.maven.org/maven2/org/fusesource/jansi/jansi/2.4.1/jansi-2.4.1.jar","name":"org_fusesource_jansi_jansi","actual":"@org_fusesource_jansi_jansi//jar","bind": "jar/org/fusesource/jansi/jansi"},
        {"artifact":"org.hamcrest:hamcrest-core:1.3","url":"https://repo1.maven.org/maven2/org/hamcrest/hamcrest-core/1.3/hamcrest-core-1.3.jar","name":"org_hamcrest_hamcrest_core","actual":"@org_hamcrest_hamcrest_core//jar","bind": "jar/org/hamcrest/hamcrest_core"},
        {"artifact":"org.jacoco:org.jacoco.core:0.8.12","url":"https://repo1.maven.org/maven2/org/jacoco/org.jacoco.core/0.8.12/org.jacoco.core-0.8.12.jar","name":"org_jacoco_org_jacoco_core","actual":"@org_jacoco_org_jacoco_core//jar","bind": "jar/org/jacoco/org_jacoco_core"},
        {"artifact":"org.jline:jline-native:3.28.0","url":"https://repo1.maven.org/maven2/org/jline/jline-native/3.28.0/jline-native-3.28.0.jar","name":"org_jline_jline_native","actual":"@org_jline_jline_native//jar","bind": "jar/org/jline/jline_native"},
        {"artifact":"org.jline:jline-reader:3.28.0","url":"https://repo1.maven.org/maven2/org/jline/jline-reader/3.28.0/jline-reader-3.28.0.jar","name":"org_jline_jline_reader","actual":"@org_jline_jline_reader//jar","bind": "jar/org/jline/jline_reader"},
        {"artifact":"org.jline:jline-terminal:3.28.0","url":"https://repo1.maven.org/maven2/org/jline/jline-terminal/3.28.0/jline-terminal-3.28.0.jar","name":"org_jline_jline_terminal","actual":"@org_jline_jline_terminal//jar","bind": "jar/org/jline/jline_terminal"},
        {"artifact":"org.jline:jline-terminal-jansi:3.24.1","url":"https://repo1.maven.org/maven2/org/jline/jline-terminal-jansi/3.24.1/jline-terminal-jansi-3.24.1.jar","name":"org_jline_jline_terminal_jansi","actual":"@org_jline_jline_terminal_jansi//jar","bind": "jar/org/jline/jline_terminal_jansi"},
        {"artifact":"org.jline:jline-terminal-jna:3.24.1","url":"https://repo1.maven.org/maven2/org/jline/jline-terminal-jna/3.24.1/jline-terminal-jna-3.24.1.jar","name":"org_jline_jline_terminal_jna","actual":"@org_jline_jline_terminal_jna//jar","bind": "jar/org/jline/jline_terminal_jna"},
        {"artifact":"org.ow2.asm:asm:9.7","url":"https://repo1.maven.org/maven2/org/ow2/asm/asm/9.7/asm-9.7.jar","name":"org_ow2_asm_asm","actual":"@org_ow2_asm_asm//jar","bind": "jar/org/ow2/asm/asm"},
        {"artifact":"org.ow2.asm:asm-commons:9.7","url":"https://repo1.maven.org/maven2/org/ow2/asm/asm-commons/9.7/asm-commons-9.7.jar","name":"org_ow2_asm_asm_commons","actual":"@org_ow2_asm_asm_commons//jar","bind": "jar/org/ow2/asm/asm_commons"},
        {"artifact":"org.ow2.asm:asm-tree:9.7","url":"https://repo1.maven.org/maven2/org/ow2/asm/asm-tree/9.7/asm-tree-9.7.jar","name":"org_ow2_asm_asm_tree","actual":"@org_ow2_asm_asm_tree//jar","bind": "jar/org/ow2/asm/asm_tree"},
        {"artifact":"org.scala-lang.modules:scala-collection-compat_3:2.11.0","url":"https://repo1.maven.org/maven2/org/scala-lang/modules/scala-collection-compat_3/2.11.0/scala-collection-compat_3-2.11.0.jar","name":"org_scala_lang_modules_scala_collection_compat_3","actual":"@org_scala_lang_modules_scala_collection_compat_3//jar","bind": "jar/org/scala_lang/modules/scala_collection_compat_3"},
        {"artifact":"org.scala-lang.modules:scala-parallel-collections_3:1.0.4","url":"https://repo1.maven.org/maven2/org/scala-lang/modules/scala-parallel-collections_3/1.0.4/scala-parallel-collections_3-1.0.4.jar","name":"org_scala_lang_modules_scala_parallel_collections_3","actual":"@org_scala_lang_modules_scala_parallel_collections_3//jar","bind": "jar/org/scala_lang/modules/scala_parallel_collections_3"},
        {"artifact":"org.scala-lang.modules:scala-parser-combinators_3:2.1.0","url":"https://repo1.maven.org/maven2/org/scala-lang/modules/scala-parser-combinators_3/2.1.0/scala-parser-combinators_3-2.1.0.jar","name":"org_scala_lang_modules_scala_parser_combinators_3","actual":"@org_scala_lang_modules_scala_parser_combinators_3//jar","bind": "jar/org/scala_lang/modules/scala_parser_combinators_3"},
        {"artifact":"org.scala-lang.modules:scala-xml_3:2.3.0","url":"https://repo1.maven.org/maven2/org/scala-lang/modules/scala-xml_3/2.3.0/scala-xml_3-2.3.0.jar","name":"org_scala_lang_modules_scala_xml_3","actual":"@org_scala_lang_modules_scala_xml_3//jar","bind": "jar/org/scala_lang/modules/scala_xml_3"},
        {"artifact":"org.scala-sbt:compiler-interface:2.0.0-M1","url":"https://repo1.maven.org/maven2/org/scala-sbt/compiler-interface/2.0.0-M1/compiler-interface-2.0.0-M1.jar","name":"org_scala_sbt_compiler_interface","actual":"@org_scala_sbt_compiler_interface//jar","bind": "jar/org/scala_sbt/compiler_interface"},
        {"artifact":"org.scala-sbt:io_3:1.10.0","url":"https://repo1.maven.org/maven2/org/scala-sbt/io_3/1.10.0/io_3-1.10.0.jar","name":"org_scala_sbt_io_3","actual":"@org_scala_sbt_io_3//jar","bind": "jar/org/scala_sbt/io_3"},
        {"artifact":"org.scala-sbt.jline:jline:2.14.7-sbt-9c3b6aca11c57e339441442bbf58e550cdfecb79","url":"https://repo1.maven.org/maven2/org/scala-sbt/jline/jline/2.14.7-sbt-9c3b6aca11c57e339441442bbf58e550cdfecb79/jline-2.14.7-sbt-9c3b6aca11c57e339441442bbf58e550cdfecb79.jar","name":"org_scala_sbt_jline_jline","actual":"@org_scala_sbt_jline_jline//jar","bind": "jar/org/scala_sbt/jline/jline"},
        {"artifact":"org.scala-sbt:launcher-interface:1.4.3","url":"https://repo1.maven.org/maven2/org/scala-sbt/launcher-interface/1.4.3/launcher-interface-1.4.3.jar","name":"org_scala_sbt_launcher_interface","actual":"@org_scala_sbt_launcher_interface//jar","bind": "jar/org/scala_sbt/launcher_interface"},
        {"artifact":"org.scala-sbt:sbinary_3:0.5.1","url":"https://repo1.maven.org/maven2/org/scala-sbt/sbinary_3/0.5.1/sbinary_3-0.5.1.jar","name":"org_scala_sbt_sbinary_3","actual":"@org_scala_sbt_sbinary_3//jar","bind": "jar/org/scala_sbt/sbinary_3"},
        {"artifact":"org.scala-sbt:test-interface:1.0","url":"https://repo1.maven.org/maven2/org/scala-sbt/test-interface/1.0/test-interface-1.0.jar","name":"org_scala_sbt_test_interface","actual":"@org_scala_sbt_test_interface//jar","bind": "jar/org/scala_sbt/test_interface"},
        {"artifact":"org.scala-sbt:util-control_3:2.0.0-M1","url":"https://repo1.maven.org/maven2/org/scala-sbt/util-control_3/2.0.0-M1/util-control_3-2.0.0-M1.jar","name":"org_scala_sbt_util_control_3","actual":"@org_scala_sbt_util_control_3//jar","bind": "jar/org/scala_sbt/util_control_3"},
        {"artifact":"org.scala-sbt:util-core_3:2.0.0-M2","url":"https://repo1.maven.org/maven2/org/scala-sbt/util-core_3/2.0.0-M2/util-core_3-2.0.0-M2.jar","name":"org_scala_sbt_util_core_3","actual":"@org_scala_sbt_util_core_3//jar","bind": "jar/org/scala_sbt/util_core_3"},
        {"artifact":"org.scala-sbt:util-interface:2.0.0-M2","url":"https://repo1.maven.org/maven2/org/scala-sbt/util-interface/2.0.0-M2/util-interface-2.0.0-M2.jar","name":"org_scala_sbt_util_interface","actual":"@org_scala_sbt_util_interface//jar","bind": "jar/org/scala_sbt/util_interface"},
        {"artifact":"org.scala-sbt:util-logging_3:2.0.0-M2","url":"https://repo1.maven.org/maven2/org/scala-sbt/util-logging_3/2.0.0-M2/util-logging_3-2.0.0-M2.jar","name":"org_scala_sbt_util_logging_3","actual":"@org_scala_sbt_util_logging_3//jar","bind": "jar/org/scala_sbt/util_logging_3"},
        {"artifact":"org.scala-sbt:util-relation_3:2.0.0-M2","url":"https://repo1.maven.org/maven2/org/scala-sbt/util-relation_3/2.0.0-M2/util-relation_3-2.0.0-M2.jar","name":"org_scala_sbt_util_relation_3","actual":"@org_scala_sbt_util_relation_3//jar","bind": "jar/org/scala_sbt/util_relation_3"},
        {"artifact":"org.scala-sbt:zinc_3:2.0.0-M1","url":"https://repo1.maven.org/maven2/org/scala-sbt/zinc_3/2.0.0-M1/zinc_3-2.0.0-M1.jar","name":"org_scala_sbt_zinc_3","actual":"@org_scala_sbt_zinc_3//jar","bind": "jar/org/scala_sbt/zinc_3"},
        {"artifact":"org.scala-sbt:zinc-apiinfo_3:2.0.0-M1","url":"https://repo1.maven.org/maven2/org/scala-sbt/zinc-apiinfo_3/2.0.0-M1/zinc-apiinfo_3-2.0.0-M1.jar","name":"org_scala_sbt_zinc_apiinfo_3","actual":"@org_scala_sbt_zinc_apiinfo_3//jar","bind": "jar/org/scala_sbt/zinc_apiinfo_3"},
        {"artifact":"org.scala-sbt:zinc-classfile_3:2.0.0-M1","url":"https://repo1.maven.org/maven2/org/scala-sbt/zinc-classfile_3/2.0.0-M1/zinc-classfile_3-2.0.0-M1.jar","name":"org_scala_sbt_zinc_classfile_3","actual":"@org_scala_sbt_zinc_classfile_3//jar","bind": "jar/org/scala_sbt/zinc_classfile_3"},
        {"artifact":"org.scala-sbt:zinc-classpath_3:2.0.0-M1","url":"https://repo1.maven.org/maven2/org/scala-sbt/zinc-classpath_3/2.0.0-M1/zinc-classpath_3-2.0.0-M1.jar","name":"org_scala_sbt_zinc_classpath_3","actual":"@org_scala_sbt_zinc_classpath_3//jar","bind": "jar/org/scala_sbt/zinc_classpath_3"},
        {"artifact":"org.scala-sbt:zinc-compile-core_3:2.0.0-M1","url":"https://repo1.maven.org/maven2/org/scala-sbt/zinc-compile-core_3/2.0.0-M1/zinc-compile-core_3-2.0.0-M1.jar","name":"org_scala_sbt_zinc_compile_core_3","actual":"@org_scala_sbt_zinc_compile_core_3//jar","bind": "jar/org/scala_sbt/zinc_compile_core_3"},
        {"artifact":"org.scala-sbt:zinc-core_3:2.0.0-M1","url":"https://repo1.maven.org/maven2/org/scala-sbt/zinc-core_3/2.0.0-M1/zinc-core_3-2.0.0-M1.jar","name":"org_scala_sbt_zinc_core_3","actual":"@org_scala_sbt_zinc_core_3//jar","bind": "jar/org/scala_sbt/zinc_core_3"},
        {"artifact":"org.scala-sbt:zinc-persist_3:2.0.0-M1","url":"https://repo1.maven.org/maven2/org/scala-sbt/zinc-persist_3/2.0.0-M1/zinc-persist_3-2.0.0-M1.jar","name":"org_scala_sbt_zinc_persist_3","actual":"@org_scala_sbt_zinc_persist_3//jar","bind": "jar/org/scala_sbt/zinc_persist_3"},
        {"artifact":"org.scala-sbt:zinc-persist-core-assembly:2.0.0-alpha14","url":"https://repo1.maven.org/maven2/org/scala-sbt/zinc-persist-core-assembly/2.0.0-alpha14/zinc-persist-core-assembly-2.0.0-alpha14.jar","name":"org_scala_sbt_zinc_persist_core_assembly","actual":"@org_scala_sbt_zinc_persist_core_assembly//jar","bind": "jar/org/scala_sbt/zinc_persist_core_assembly"},
        {"artifact":"org.scalameta:junit-interface:1.0.3","url":"https://repo1.maven.org/maven2/org/scalameta/junit-interface/1.0.3/junit-interface-1.0.3.jar","name":"org_scalameta_junit_interface","actual":"@org_scalameta_junit_interface//jar","bind": "jar/org/scalameta/junit_interface"},
        {"artifact":"org.scalameta:munit_3:1.0.3","url":"https://repo1.maven.org/maven2/org/scalameta/munit_3/1.0.3/munit_3-1.0.3.jar","name":"org_scalameta_munit_3","actual":"@org_scalameta_munit_3//jar","bind": "jar/org/scalameta/munit_3"},
        {"artifact":"org.scalameta:munit-diff_3:1.0.3","url":"https://repo1.maven.org/maven2/org/scalameta/munit-diff_3/1.0.3/munit-diff_3-1.0.3.jar","name":"org_scalameta_munit_diff_3","actual":"@org_scalameta_munit_diff_3//jar","bind": "jar/org/scalameta/munit_diff_3"},
    ]

def maven_dependencies(callback = jar_artifact_callback):
    for hash in list_dependencies():
        callback(hash)
