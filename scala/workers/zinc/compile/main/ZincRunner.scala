package rules_scala
package workers.zinc.compile

import java.io.{File, PrintWriter}
import java.lang.ref.WeakReference
import java.net.URLClassLoader
import java.nio.file.{Files, NoSuchFileException, Path, Paths}
import java.text.SimpleDateFormat
import java.util.{Date, List as JList, Optional, Properties}

import scala.jdk.CollectionConverters.*
import scala.jdk.OptionConverters.RichOptional
import scala.util.Try
import scala.util.control.NonFatal

import com.google.devtools.build.buildjar.jarhelper.JarCreator
import sbt.internal.inc.{Analysis, AnalyzingCompiler, CompileFailed, IncrementalCompilerImpl, Locate, PlainVirtualFile, ZincUtil}
import sbt.internal.inc.classpath.ClassLoaderCache
import sbt.internal.inc.{Stamps, SourceInfos}
import scopt.{DefaultOParserSetup, OParser, OParserSetup}
import xsbti.{Logger, PathBasedFile, Problem, Severity, VirtualFile}
import xsbti.compile.{
  AnalysisContents,
  ClasspathOptionsUtil,
  CompileAnalysis,
  CompileOptions,
  CompileProgress,
  CompilerCache,
  DefinesClass,
  IncOptions,
  Inputs,
  MiniSetup,
  PerClasspathEntryLookup,
  PreviousResult,
  ScalaJSFiles,
  Setup,
  TastyFiles
}

import rules_scala.common.worker.WorkerMain
import rules_scala.workers.common.*
import rules_scala.workers.zinc.diagnostics.Diagnostics;

extension (problem: Problem)
  def toDiagnostic: Diagnostics.Diagnostic =
    def e =
      val path = problem.position.sourcePath.toScala.fold("no data")(identity)
      val line = problem.position.line.toScala.fold("no data")(identity)
      val pointer = problem.position.pointer.toScala.fold("no data")(identity)
      sys.error(s"The compilation Problem($path:$line:$pointer) does not contain enough information. Failed to create compilation diagnostics.")

    Diagnostics.Diagnostic.newBuilder
      .setSeverity {
        problem.severity match
          case Severity.Error => Diagnostics.Severity.ERROR
          case Severity.Warn  => Diagnostics.Severity.WARNING
          case Severity.Info  => Diagnostics.Severity.INFORMATION
      }
      .setMessage(problem.message)
      .setRange {
        Diagnostics.Range.newBuilder
          .setStart(
            Diagnostics.Position.newBuilder
              .setLine(problem.position.startLine().toScala.fold(e)(identity) - 1)
              .setCharacter(problem.position.startColumn().toScala.fold(e)(identity))
              .build
          )
          .setEnd(
            Diagnostics.Position.newBuilder
              .setLine(problem.position.endLine().toScala.fold(e)(identity) - 1)
              .setCharacter(problem.position.endColumn().toScala.fold(e)(identity))
              .build
          )
          .build
      }
      .build

final case class AnalysisArgument(label: String, apis: Path, relations: Path, jars: Vector[Path])
object AnalysisArgument:
  def from(value: String): AnalysisArgument = value.split(',') match
    case Array(prefixedLabel, apis, relations, jars*) =>
      AnalysisArgument(prefixedLabel.stripPrefix("_"), Paths.get(apis), Paths.get(relations), jars.map(Paths.get(_)).toVector)

/** <strong>Caching</strong>
  *
  * Zinc has two caches:
  *   1. a ClassLoaderCache which is a soft reference cache for classloaders of Scala compilers. 2. a CompilerCache which is a hard reference cache
  *      for (I think) Scala compiler instances.
  *
  * The CompilerCache has reproducibility issues, so it needs to be a no-op. The ClassLoaderCache needs to be reused else JIT reuse (i.e. the point of
  * the worker strategy) doesn't happen.
  *
  * There are two sensible strategies for Bazel workers A. Each worker compiles multiple Scala versions. Trust the ClassLoaderCache's timestamp check.
  * Maintain a hard reference to the classloader for the last version, and allow previous versions to be GC'ed subject to free memory and
  * -XX:SoftRefLRUPolicyMSPerMB. B. Each worker compiles a single Scala version. Probably still use ClassLoaderCache + hard reference since
  * ClassLoaderCache is hard to remove. The compiler classpath is passed via the initial flags to the worker (rather than the per-request arg file).
  * Bazel worker management cycles out Scala compiler versions. Currently, this runner follows strategy A.
  */
object ZincRunner extends WorkerMain[ZincRunner.Arguments]:

  private lazy val _: Class[sbt.internal.util.Relation[?, ?]] = classOf[sbt.internal.util.Relation[?, ?]]
  
  override def init(args: collection.Seq[String]): Arguments =
    Arguments(args).getOrElse(throw IllegalArgumentException(s"init args is invalid: ${args.mkString(" ")}"))

  override def work(workerArgs: Arguments, args: collection.Seq[String]): Unit =
    val workArgs = WorkArguments(args).getOrElse(throw IllegalArgumentException(s"work args is invalid: ${args.mkString(" ")}"))

    given ctx: ZincContext = ZincContext(
      rootDir = Paths.get("").toAbsolutePath,
      tmpDir = workArgs.tmpDir.toAbsolutePath,
      depsCache = workerArgs.depsCache.map(_.toAbsolutePath).orNull
    )

    given logger: AnnexLogger = AnnexLogger(workArgs.logLevel)

    val reporter = LoggedReporter(logger)

    val sourcesDir = workArgs.tmpDir.resolve("src")
    val sources: collection.Seq[File] = workArgs.sources ++ workArgs.sourceJars.zipWithIndex
      .flatMap((jar, i) => FileUtil.extractZip(jar, sourcesDir.resolve(i.toString)))

    // extract upstream classes
    val classesDir = workArgs.tmpDir.resolve("classes")

    val analyses = workerArgs.usePersistence match
      case true =>
        workArgs.analysis
          .flatMap: arg =>
            arg.jars.distinct.map :jar =>
              jar -> (classesDir.resolve(labelToPath(arg.label)), DepAnalysisFiles(arg.apis, arg.relations))
          .toMap
      case false => Map.empty
    val deps = Deps.create(workerArgs.depsCache, workArgs.classpath, analyses)

    // load persisted files
    val analysisFiles = AnalysisFiles(
      apis = workArgs.outputApis,
      miniSetup = workArgs.outputSetup,
      relations = workArgs.outputRelations,
      sourceInfos = workArgs.outputInfos,
      stamps = workArgs.outputStamps
    )
    val analysesFormat = AnxAnalyses(if workArgs.debug then AnxAnalysisStore.TextFormat else AnxAnalysisStore.BinaryFormat)
    val analysisStore = AnxAnalysisStore(analysisFiles, analysesFormat)

    val persistence = workerArgs.persistenceDir.fold[ZincPersistence](NullPersistence): rootDir =>
      val path = labelToPath(workArgs.label)
      FilePersistence(rootDir.resolve(path), analysisFiles, workArgs.outputJar)

    val classesOutputDir = classesDir.resolve(labelToPath(workArgs.label))
    Files.createDirectories(classesOutputDir)
    try
      persistence.load()
      if Files.exists(workArgs.outputJar) then
        FileUtil.extractZip(workArgs.outputJar, classesOutputDir)
        Files.delete(workArgs.outputJar)
    catch
      case NonFatal(e) =>
        logger.warn(() => s"Failed to load cached analysis: $e")
        Files.delete(analysisFiles.apis)
        Files.delete(analysisFiles.miniSetup)
        Files.delete(analysisFiles.relations)
        Files.delete(analysisFiles.sourceInfos)
        Files.delete(analysisFiles.stamps)
        FileUtil.delete(classesOutputDir)

    val previousResult = Try(analysisStore.get())
      .fold(
        { e =>
          logger.warn(() => s"Failed to load previous analysis: $e")
          Optional.empty[AnalysisContents]()
        },
        identity
      )
      .map[PreviousResult](contents => PreviousResult.of(Optional.of(contents.getAnalysis), Optional.of(contents.getMiniSetup)))
      .orElseGet(() => PreviousResult.of(Optional.empty[CompileAnalysis](), Optional.empty[MiniSetup]()))

    // setup compiler
    val scalaInstance = AnnexScalaInstance(topLoader, classloaderCache, workArgs.compilerClasspath.toArray)

    val compileOptions = CompileOptions.create
      .withSources(sources.map(f => PlainVirtualFile(f.toPath())).toArray)
      .withClasspath((classesOutputDir +: deps.map(_.classpath)).map(PlainVirtualFile(_)).toArray)
      .withClassesDirectory(classesOutputDir)
      .withJavacOptions(workArgs.javaCompilerOption.toArray)
      .withScalacOptions((workArgs.plugins.map(p => s"-Xplugin:$p") ++ workArgs.compilerOption).toArray)

    val scalaCompiler = ZincUtil
      .scalaCompiler(scalaInstance, workArgs.compilerBridge)
      .withClassLoaderCache(classloaderCache)
    val compilers = ZincUtil.compilers(scalaInstance, ClasspathOptionsUtil.boot, None, scalaCompiler)

    val depMap = deps
      .collect:
        case ExternalDep(_, classpath, files) => classpath -> files
        case ExternalCachedDep(_, _, classpath, files) => classpath -> files
      .toMap
    val lookup = AnxPerClasspathEntryLookup: file =>
      depMap
        .get(file)
        .map: files =>
          Analysis.Empty.copy(
            stamps = Stamps.empty,
            apis = analysesFormat.apis(files.apis).read(files.apis),
            relations = analysesFormat.relations(files.relations).read(files.relations),
            infos = SourceInfos.empty,
            compilations = sbt.internal.inc.Compilations.empty
          )

    val setup =
      val incOptions = IncOptions.create()
        .withEnabled(true)
        .withPipelining(true)
        .withAuxiliaryClassFiles(Array(TastyFiles.instance(), ScalaJSFiles.instance()))
        // .withStrictMode(true)
        // .withRelationsDebug(true)
        // .withApiDebug(true)
      val skip = false
      val zincFile: Path = null

      Setup.create(
        lookup,
        skip,
        zincFile,
        compilerCache,
        incOptions,
        reporter,
        Optional.empty[CompileProgress](),
        Array.empty[xsbti.T2[String, String]]
      )

    val inputs = Inputs.of(compilers, compileOptions, setup, previousResult)

    // compile
    val incrementalCompiler = IncrementalCompilerImpl()
    val compileResult =
      try incrementalCompiler.compile(inputs, logger)
      catch
        case _: CompileFailed => sys.exit(-1)
        case e: MatchError =>
          e.printStackTrace()
          System.err.println(e)
          sys.exit(1)
        case e: ClassFormatError =>
          System.err.println(e)
          sys.exit(1)
        case e: NoClassDefFoundError =>
          System.err.println(s"workArgs:$workArgs")
          System.err.println(e)
          sys.exit(1)
      finally
        // create compiler diagnostics
        if workArgs.enableDiagnostics then
          val targetDiagnostics: Diagnostics.TargetDiagnostics =
            Diagnostics.TargetDiagnostics.newBuilder.addAllDiagnostics {
              reporter.problems
                .groupBy(
                  _.position.sourcePath.toScala
                    .fold(sys.error("The compilation problem does not contain the path to the source. Failed to create compilation diagnostics."))(
                      identity
                    )
                )
                .map { case (path, problems) =>
                  Diagnostics.FileDiagnostics.newBuilder
                    .setPath("workspace-root://" + path)
                    .addAllDiagnostics(problems.map(_.toDiagnostic).toList.asJava)
                    .build
                }
                .asJava
            }.build
          Files.write(workArgs.diagnosticsFile, targetDiagnostics.toByteArray)

    // create analyses
    analysisStore.set(AnalysisContents.create(compileResult.analysis, compileResult.setup))

    // create used deps
    val analysis = compileResult.analysis.asInstanceOf[Analysis]

    val usedDeps =
      deps.filter(Deps.used(deps, analysis.relations, lookup)).filter(_.file != scalaInstance.libraryJar.toPath)
    Files.write(workArgs.outputUsed, usedDeps.map(_.file.toString).sorted.asJava)

    // create jar
    val mains = analysis.infos.allInfos.values
      .flatMap(_.getMainClasses)
      .toSeq
      .sorted

    val pw = PrintWriter(workArgs.mainManifest)
    try mains.foreach(pw.println)
    finally pw.close()

    val jarCreator = JarCreator(workArgs.outputJar)
    jarCreator.addDirectory(classesOutputDir)
    jarCreator.setCompression(false)
    jarCreator.setNormalize(true)
    jarCreator.setVerbose(false)
    for main <- mains.headOption do jarCreator.setMainClass(main)
    jarCreator.execute()

    // save persisted files
    if workerArgs.usePersistence then
      try persistence.save()
      catch case NonFatal(e) => logger.warn(() => s"Failed to save cached analysis: $e")

    // clear temporary files
    FileUtil.delete(workArgs.tmpDir)
    Files.createDirectory(workArgs.tmpDir)

  final case class Arguments(
      usePersistence: Boolean = true,
      depsCache: Option[Path] = None,
      persistenceDir: Option[Path] = None,
      maxErrors: Int = 100
  )
  object Arguments:
    private val builder = OParser.builder[Arguments]
    import builder.*

    private val parser = OParser.sequence(
      opt[Boolean]("use_persistence").unbounded().action((p, c) => c.copy(usePersistence = p)),
      opt[String]("extracted_file_cache").unbounded().optional().action((p, c) => c.copy(depsCache = Some(pathFrom(p)))),
      opt[String]("persistence_dir").unbounded().optional().action((p, c) => c.copy(persistenceDir = Some(pathFrom(p)))),
      opt[Int]("max_errors").optional().action((m, c) => c.copy(maxErrors = m))
    )

    def apply(args: collection.Seq[String]): Option[Arguments] =
      OParser.parse(parser, args, Arguments())

    private def pathFrom(path: String): Path = Paths.get(path.replace("~", sys.props.getOrElse("user.home", "")))
  end Arguments

  final case class WorkArguments(
      analysis: Vector[AnalysisArgument] = Vector.empty,
      classpath: Vector[Path] = Vector.empty,
      compilerBridge: File = new File("."),
      compilerClasspath: Vector[File] = Vector.empty,
      compilerOption: Vector[String] = Vector.empty,
      debug: Boolean = false,
      javaCompilerOption: Vector[String] = Vector.empty,
      label: String = "",
      logLevel: LogLevel = LogLevel.Debug,
      enableDiagnostics: Boolean = false,
      diagnosticsFile: Path = Paths.get("."),
      mainManifest: File = new File("."),
      outputApis: Path = Paths.get("."),
      outputInfos: Path = Paths.get("."),
      outputJar: Path = Paths.get("."),
      outputRelations: Path = Paths.get("."),
      outputSetup: Path = Paths.get("."),
      outputStamps: Path = Paths.get("."),
      outputUsed: Path = Paths.get("."),
      plugins: Vector[File] = Vector.empty,
      sourceJars: Vector[Path] = Vector.empty,
      sources: Vector[File] = Vector.empty,
      tmpDir: Path = Paths.get(".")
  ) extends PrettyProduct
  object WorkArguments:
    private val builder = OParser.builder[WorkArguments]
    import builder.*

    private val parser = OParser.sequence(
      opt[LogLevel]("log_level")
        .optional()
        .action((lvl, c) => c.copy(logLevel = lvl))
        .text("Log level"),
      opt[File]("source_jar")
        .unbounded()
        .optional()
        .action((s, c) => c.copy(sourceJars = c.sourceJars :+ s.toPath()))
        .text("Source jars"),
      opt[File]("tmp")
        .required()
        .action((tmp, c) => c.copy(tmpDir = tmp.toPath))
        .text("Temporary directory"),
      opt[File]("output_jar")
        .required()
        .action((jar, c) => c.copy(outputJar = jar.toPath))
        .text("Output jar"),
      opt[String]("analysis")
        .unbounded()
        .optional()
        .valueName("args")
        .action((arg, c) => c.copy(analysis = c.analysis :+ AnalysisArgument.from(arg)))
        .text("Analysis, given as: label apis relations [jar ...]"),
      opt[File]("cp")
        .unbounded()
        .optional()
        .action((cp, c) => c.copy(classpath = c.classpath :+ cp.toPath))
        .text("Compilation classpath"),
      opt[File]("compiler_cp")
        .unbounded()
        .optional()
        .action((cp, c) => c.copy(compilerClasspath = c.compilerClasspath :+ cp))
        .text("Compiler classpath"),
      opt[File]("output_apis")
        .required()
        .action((out, c) => c.copy(outputApis = out.toPath))
        .text("Output APIs"),
      opt[File]("output_setup")
        .required()
        .action((out, c) => c.copy(outputSetup = out.toPath))
        .text("Output Zinc setup"),
      opt[File]("output_relations")
        .required()
        .action((out, c) => c.copy(outputRelations = out.toPath))
        .text("Output Zinc relations"),
      opt[File]("output_infos")
        .required()
        .action((out, c) => c.copy(outputInfos = out.toPath))
        .text("Output Zinc source infos"),
      opt[File]("output_stamps")
        .required()
        .action((out, c) => c.copy(outputStamps = out.toPath))
        .text("Output Zinc source stamps"),
      opt[Boolean]("debug").action((debug, c) => c.copy(debug = debug)),
      opt[String]("label")
        .required()
        .action((l, c) => c.copy(label = l))
        .text("Bazel label"),
      opt[File]("compiler_bridge")
        .required()
        .action((bridge, c) => c.copy(compilerBridge = bridge))
        .text("Compiler bridge"),
      opt[File]("main_manifest")
        .required()
        .action((manifest, c) => c.copy(mainManifest = manifest))
        .text("List of main entry points"),
      opt[File]("output_used")
        .required()
        .action((out, c) => c.copy(outputUsed = out.toPath))
        .text("Output list of used jars"),
      opt[String]("compiler_option")
        .unbounded()
        .optional()
        .action((opt, c) => c.copy(compilerOption = c.compilerOption :+ opt))
        .text("Compiler option"),
      opt[String]("java_compiler_option")
        .unbounded()
        .optional()
        .action((opt, c) => c.copy(javaCompilerOption = c.javaCompilerOption :+ opt))
        .text("Java compiler option"),
      opt[File]("plugin")
        .unbounded()
        .optional()
        .action((p, c) => c.copy(plugins = c.plugins :+ p))
        .text("Compiler plugins"),
      arg[File]("<source>...")
        .unbounded()
        .optional()
        .action((s, c) => c.copy(sources = c.sources :+ s))
        .text("Source files"),
      opt[Boolean]("enable_diagnostics").action((value, c) => c.copy(enableDiagnostics = value)),
      opt[File]("diagnostics_file")
        .optional()
        .action((value, c) => c.copy(diagnosticsFile = value.toPath))
        .text("Compilation diagnostics file"),

      checkConfig { c =>
        if c.enableDiagnostics && c.diagnosticsFile == Paths.get(".") then
          failure("If enable_diagnostics is true, diagnostics_file must be specified")
        else
          success
      }
    )

    def apply(args: collection.Seq[String]): Option[WorkArguments] =
      val setup: OParserSetup = new DefaultOParserSetup:
        override def showUsageOnError = Some(true)
        override def errorOnUnknownArgument = false
      OParser.parse(parser, args, WorkArguments(), setup)
  end WorkArguments

  private val topLoader = TopClassLoader(getClass().getClassLoader())
  private val classloaderCache = ClassLoaderCache(URLClassLoader(Array.empty))

  private val compilerCache = CompilerCache.fresh

private def labelToPath(label: String): Path =
  Paths.get(label.replaceAll("^/+", "").replaceAll(raw"[^\w/]", "_").dropWhile(ch => ch == '/' || ch == '_'))

final class AnxPerClasspathEntryLookup(analyses: Path => Option[CompileAnalysis]) extends PerClasspathEntryLookup:
  private val Empty = Optional.empty[CompileAnalysis]

  override def analysis(classpathEntry: VirtualFile): Optional[CompileAnalysis] =
    classpathEntry match
      case file: PathBasedFile => analyses(file.toPath()).fold(Empty)(Optional.of(_))
      case _                   => Empty
  override def definesClass(classpathEntry: VirtualFile): DefinesClass =
    Locate.definesClass(classpathEntry)
