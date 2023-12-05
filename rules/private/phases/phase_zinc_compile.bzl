load(
    "@rules_scala3//rules:providers.bzl",
    _ZincInfo = "ZincInfo",
)
load(
    "@rules_scala3//rules/common:private/utils.bzl",
    _resolve_execution_reqs = "resolve_execution_reqs",
)
load("//rules/common:private/get_toolchain.bzl", "get_toolchain")

#
# PHASE: compile
#
# Compiles Scala sources ;)
#

def phase_zinc_compile(ctx, g):
    toolchain = get_toolchain(ctx)

    apis = ctx.actions.declare_file("{}/apis.gz".format(ctx.label.name))
    infos = ctx.actions.declare_file("{}/infos.gz".format(ctx.label.name))
    mains_file = ctx.actions.declare_file("{}.jar.mains.txt".format(ctx.label.name))
    relations = ctx.actions.declare_file("{}/relations.gz".format(ctx.label.name))
    setup = ctx.actions.declare_file("{}/setup.gz".format(ctx.label.name))
    stamps = ctx.actions.declare_file("{}/stamps.gz".format(ctx.label.name))
    used = ctx.actions.declare_file("{}/deps_used.txt".format(ctx.label.name))

    tmp = ctx.actions.declare_directory("{}/tmp".format(ctx.label.name))

    javacopts = [
        ctx.expand_location(option, ctx.attr.data)
        for option in ctx.attr.javacopts + java_common.default_javac_opts(
            java_toolchain = ctx.attr._java_toolchain[java_common.JavaToolchainInfo],
        )
    ]

    zincs = [dep[_ZincInfo] for dep in ctx.attr.deps if _ZincInfo in dep]

    args = ctx.actions.args()
    args.add_all(depset(transitive = [zinc.deps for zinc in zincs]), map_each = _compile_analysis)
    args.add("--compiler_bridge", toolchain.compiler_bridge)
    args.add_all(g.classpaths.compiler, format_each = "--compiler_cp=%s")
    args.add_all(g.classpaths.compile, format_each = "--cp=%s")
    args.add_all(toolchain.global_scalacopts, format_each = "--compiler_option=%s")
    args.add_all(ctx.attr.scalacopts, format_each = "--compiler_option=%s")
    args.add_all(javacopts, format_each = "--java_compiler_option=%s")
    args.add(ctx.label, format = "--label=%s")
    args.add("--main_manifest", mains_file)
    args.add("--output_apis", apis)
    args.add("--output_infos", infos)
    args.add("--output_jar", g.classpaths.jar)
    args.add("--output_relations", relations)
    args.add("--output_setup", setup)
    args.add("--output_stamps", stamps)
    args.add("--output_used", used)
    args.add_all(g.classpaths.plugin, format_each = "--plugin=%s")
    args.add_all(g.classpaths.src_jars, format_each = "--source_jar=%s")
    args.add("--tmp", tmp.path)
    args.add("--log_level", toolchain.zinc_log_level)
    args.add_all(g.classpaths.srcs)
    args.set_param_file_format("multiline")
    args.use_param_file("@%s", use_always = True)

    worker_args = toolchain.global_jvm_flags
    worker = toolchain.compile_worker

    worker_inputs, _, input_manifests = ctx.resolve_command(tools = [worker])
    inputs = depset(
        [toolchain.compiler_bridge] + ctx.files.data + ctx.files.srcs + worker_inputs,
        transitive = [
            g.classpaths.plugin,
            g.classpaths.compile,
            g.classpaths.compiler,
        ] + [zinc.deps_files for zinc in zincs],
    )

    outputs = [g.classpaths.jar, mains_file, apis, infos, relations, setup, stamps, used, tmp]

    # todo: different execution path for nosrc jar?
    ctx.actions.run(
        mnemonic = "ScalaCompile",
        inputs = inputs,
        outputs = outputs,
        executable = worker.files_to_run.executable,
        input_manifests = input_manifests,
        execution_requirements = _resolve_execution_reqs(ctx, {"no-sandbox": "1", "supports-workers": "1"}),
        arguments = worker_args + [args],
        use_default_shell_env = True,
    )

    jars = []
    for jar in g.javainfo.java_info.outputs.jars:
        jars.append(jar.class_jar)
        jars.append(jar.ijar)
    zinc_info = _ZincInfo(
        apis = apis,
        deps_files = depset([apis, relations], transitive = [zinc.deps_files for zinc in zincs]),
        label = ctx.label,
        relations = relations,
        deps = depset(
            [struct(
                apis = apis,
                jars = tuple(jars),
                label = ctx.label,
                relations = relations,
            )],
            transitive = [zinc.deps for zinc in zincs],
        ),
    )

    g.out.providers.append(zinc_info)
    return struct(
        mains_file = mains_file,
        used = used,
        # todo: see about cleaning up & generalizing fields below
        zinc_info = zinc_info,
    )

def _compile_analysis(analysis):
    return [
        "--analysis",
        ",".join([
            "_{}".format(analysis.label),
            analysis.apis.path,
            analysis.relations.path,
        ] + [jar.path for jar in analysis.jars]),
    ]
